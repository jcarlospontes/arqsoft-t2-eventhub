package br.uece.eventhub.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Nucleo do dominio de Inscricao.
 * - Vagas: reserva com UPDATE condicional atomico (ADR-01).
 * - Inscricao paga: Saga orquestrada aqui (ADR-03): reservar -> pagar -> confirmar | liberar.
 * - Eventos: gravados na outbox na mesma transacao do estado (ADR-04).
 */
final class RegistrationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Db db;
    private final CatalogClient catalog;
    private final PaymentClient payment;
    private final Duration hold;

    RegistrationService(Db db, CatalogClient catalog, PaymentClient payment, Duration hold) {
        this.db = db;
        this.catalog = catalog;
        this.payment = payment;
        this.hold = hold;
    }

    // ------------------------------------------------------------------ comandos

    Map<String, Object> register(String participantId, String activityId, String cardToken,
                                 String idempotencyKey, String corr) {
        if (idempotencyKey != null) {
            Optional<Map<String, Object>> previous = findByIdempotencyKey(idempotencyKey);
            if (previous.isPresent()) {
                Log.corr(corr, "requisicao repetida (Idempotency-Key=" + idempotencyKey + "): devolvendo resultado original");
                return previous.get();
            }
        }
        double price = ensureSeats(activityId, corr);
        boolean free = price == 0.0;
        UUID id = UUID.randomUUID();

        // Passo 1 - transacao local: reservar a vaga + criar a inscricao (+ evento na outbox se gratuita)
        try {
            db.tx(c -> {
                String col = free ? "confirmed" : "reserved";
                int ok = Db.update(c, "UPDATE activity_seats SET " + col + " = " + col + " + 1 "
                        + "WHERE activity_id = ? AND reserved + confirmed < capacity", activityId);
                if (ok == 0) throw new ApiException(409, "SOLD_OUT", "nao ha vagas para esta atividade");
                Db.update(c, """
                        INSERT INTO registrations (id, activity_id, participant_id, status, amount,
                                                   idempotency_key, correlation_id, hold_expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                        id, activityId, participantId, free ? "CONFIRMED" : "PENDING_PAYMENT",
                        BigDecimal.valueOf(price), idempotencyKey, corr, free ? null : Instant.now().plus(hold));
                if (free) outbox(c, id, "RegistrationConfirmed", "registration.confirmed", activityId, participantId, price, null, corr);
                return null;
            });
        } catch (Db.DuplicateKey e) {
            if (idempotencyKey != null) {
                Optional<Map<String, Object>> previous = findByIdempotencyKey(idempotencyKey);
                if (previous.isPresent()) return previous.get();
            }
            throw new ApiException(409, "ALREADY_REGISTERED", "participante ja possui inscricao ativa nesta atividade");
        }
        Log.corr(corr, "inscricao " + id + (free ? " CONFIRMED (gratuita)" : " PENDING_PAYMENT, vaga reservada"));
        if (free) return find(id);

        // Passo 2 - pagamento (sincrono, timeout + circuit breaker)
        try {
            String result = payment.pay(id, price, cardToken, corr);
            if ("APPROVED".equals(result)) {
                if (!confirm(id, corr)) payment.cancel(id, corr); // reserva expirou no meio: estorna
            } else {
                release(id, "PAYMENT_FAILED", "DECLINED", corr);
            }
        } catch (PaymentClient.Unavailable e) {
            Log.corr(corr, e.getMessage() + " -> liberando a vaga da inscricao " + id);
            release(id, "PAYMENT_FAILED", "PAYMENT_UNAVAILABLE", corr);
            payment.cancel(id, corr); // se o pagamento chegou a ser criado, nao pode ser cobrado
        }
        return find(id);
    }

    Map<String, Object> cancel(UUID id, String participantId, String corr) {
        Map<String, Object> reg = findOwned(id, participantId);
        db.tx(c -> {
            int ok = Db.update(c, "UPDATE registrations SET status='CANCELLED', updated_at=now() "
                    + "WHERE id=? AND status='CONFIRMED'", id);
            if (ok == 0) throw new ApiException(409, "NOT_CANCELLABLE", "somente inscricoes confirmadas podem ser canceladas");
            Db.update(c, "UPDATE activity_seats SET confirmed = confirmed - 1 WHERE activity_id=?", reg.get("activityId"));
            outbox(c, id, "RegistrationCancelled", "registration.cancelled", (String) reg.get("activityId"),
                    participantId, (double) reg.get("amount"), null, corr);
            return null;
        });
        Log.corr(corr, "inscricao " + id + " CANCELLED, vaga devolvida");
        return find(id);
    }

    /** PENDING_PAYMENT -> CONFIRMED. Retorna false se a inscricao nao estava mais pendente. */
    boolean confirm(UUID id, String corr) {
        boolean done = db.tx(c -> {
            Map<String, Object> r = Db.one(c, "SELECT activity_id, participant_id, amount FROM registrations WHERE id=?", id).orElseThrow();
            int ok = Db.update(c, "UPDATE registrations SET status='CONFIRMED', hold_expires_at=NULL, updated_at=now() "
                    + "WHERE id=? AND status='PENDING_PAYMENT'", id);
            if (ok == 0) return false;
            Db.update(c, "UPDATE activity_seats SET reserved = reserved - 1, confirmed = confirmed + 1 WHERE activity_id=?",
                    r.get("activity_id"));
            outbox(c, id, "RegistrationConfirmed", "registration.confirmed", (String) r.get("activity_id"),
                    (String) r.get("participant_id"), ((BigDecimal) r.get("amount")).doubleValue(), null, corr);
            return true;
        });
        Log.corr(corr, "inscricao " + id + (done ? " CONFIRMED (pagamento aprovado)" : " nao estava mais pendente"));
        return done;
    }

    /** Compensacao: PENDING_PAYMENT -> estado final, devolvendo a vaga reservada. */
    boolean release(UUID id, String newStatus, String reason, String corr) {
        boolean done = db.tx(c -> {
            Map<String, Object> r = Db.one(c, "SELECT activity_id, participant_id, amount FROM registrations WHERE id=?", id).orElseThrow();
            int ok = Db.update(c, "UPDATE registrations SET status=?, failure_reason=?, hold_expires_at=NULL, updated_at=now() "
                    + "WHERE id=? AND status='PENDING_PAYMENT'", newStatus, reason, id);
            if (ok == 0) return false;
            Db.update(c, "UPDATE activity_seats SET reserved = reserved - 1 WHERE activity_id=?", r.get("activity_id"));
            outbox(c, id, "RegistrationReleased", "registration.released", (String) r.get("activity_id"),
                    (String) r.get("participant_id"), ((BigDecimal) r.get("amount")).doubleValue(), reason, corr);
            return true;
        });
        if (done) Log.corr(corr, "inscricao " + id + " -> " + newStatus + " (" + reason + "), vaga liberada");
        return done;
    }

    /** RN02: reservas pendentes vencidas sao liberadas (protege contra falhas no meio da Saga). */
    int expireHolds() {
        List<Map<String, Object>> expired = db.tx(c -> Db.query(c,
                "SELECT id FROM registrations WHERE status='PENDING_PAYMENT' AND hold_expires_at < now()"));
        int n = 0;
        for (Map<String, Object> row : expired) {
            UUID id = (UUID) row.get("id");
            if (release(id, "EXPIRED", "HOLD_EXPIRED", "expiration-job")) {
                payment.cancel(id, "expiration-job");
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ consultas

    Map<String, Object> findOwned(UUID id, String participantId) {
        Map<String, Object> reg = find(id);
        if (!participantId.equals(reg.get("participantId"))) {   // RN07
            throw new ApiException(403, "FORBIDDEN", "inscricao pertence a outro participante");
        }
        return reg;
    }

    Map<String, Object> find(UUID id) {
        return db.tx(c -> Db.one(c, "SELECT * FROM registrations WHERE id=?", id))
                .map(RegistrationService::toJson)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "inscricao nao encontrada"));
    }

    List<Map<String, Object>> listByParticipant(String participantId) {
        return db.tx(c -> Db.query(c, "SELECT * FROM registrations WHERE participant_id=? ORDER BY created_at DESC", participantId))
                .stream().map(RegistrationService::toJson).toList();
    }

    Map<String, Object> seats(String activityId) {
        ensureSeats(activityId, "seats-query");
        Map<String, Object> r = db.tx(c -> Db.one(c, "SELECT * FROM activity_seats WHERE activity_id=?", activityId)).orElseThrow();
        int cap = (int) r.get("capacity"), res = (int) r.get("reserved"), conf = (int) r.get("confirmed");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activityId", activityId);
        m.put("capacity", cap);
        m.put("reserved", res);
        m.put("confirmed", conf);
        m.put("available", cap - res - conf);
        return m;
    }

    // ------------------------------------------------------------------ apoio

    /** Projecao local da atividade: busca no Catalogo so na primeira vez. Retorna o preco. */
    private double ensureSeats(String activityId, String corr) {
        Optional<Map<String, Object>> row = db.tx(c -> Db.one(c, "SELECT price FROM activity_seats WHERE activity_id=?", activityId));
        if (row.isPresent()) return ((BigDecimal) row.get().get("price")).doubleValue();
        CatalogClient.Activity a = catalog.getActivity(activityId, corr);
        db.tx(c -> Db.update(c, "INSERT INTO activity_seats (activity_id, capacity, price) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                a.id(), a.capacity(), BigDecimal.valueOf(a.price())));
        return a.price();
    }

    private static void outbox(Connection c, UUID registrationId, String type, String routingKey, String activityId,
                               String participantId, double amount, String reason, String corr) throws SQLException {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("registrationId", registrationId.toString());
        payload.put("activityId", activityId);
        payload.put("participantId", participantId);
        payload.put("amount", amount);
        if (reason != null) payload.put("reason", reason);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("producer", "registration-service");
        envelope.put("correlationId", corr);
        envelope.put("payload", payload);
        try {
            Db.update(c, "INSERT INTO outbox (event_id, event_type, routing_key, payload) VALUES (?,?,?,?)",
                    eventId, type, routingKey, MAPPER.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<Map<String, Object>> findByIdempotencyKey(String key) {
        return db.tx(c -> Db.one(c, "SELECT * FROM registrations WHERE idempotency_key=?", key)).map(RegistrationService::toJson);
    }

    private static Map<String, Object> toJson(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id").toString());
        m.put("activityId", r.get("activity_id"));
        m.put("participantId", r.get("participant_id"));
        m.put("status", r.get("status"));
        m.put("amount", ((BigDecimal) r.get("amount")).doubleValue());
        m.put("failureReason", r.get("failure_reason"));
        m.put("holdExpiresAt", r.get("hold_expires_at") == null ? null : r.get("hold_expires_at").toString());
        m.put("correlationId", r.get("correlation_id"));
        m.put("createdAt", r.get("created_at").toString());
        m.put("updatedAt", r.get("updated_at").toString());
        return m;
    }
}
