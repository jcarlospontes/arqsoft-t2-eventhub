package br.uece.eventhub.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registration Service (Java 21, sem framework).
 * API REST documentada em docs/openapi.yaml. Header X-Correlation-Id e propagado para
 * Catalogo, Pagamento e eventos (RQ06).
 */
public final class Main {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REG_ID = Pattern.compile("^/registrations/([0-9a-fA-F-]{36})$");
    private static final Pattern SEATS = Pattern.compile("^/activities/([a-z0-9-]+)/seats$");

    private final RegistrationService service;
    private final Jwt jwt;
    private final PaymentClient payment;

    private Main(RegistrationService service, Jwt jwt, PaymentClient payment) {
        this.service = service;
        this.jwt = jwt;
        this.payment = payment;
    }

    public static void main(String[] args) throws Exception {
        String dbUrl = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432") + "/"
                + env("DB_NAME", "eventhub") + "?currentSchema=registration";
        Db db = new Db(dbUrl, env("DB_USER", "registration"), env("DB_PASSWORD", null), 20);
        runSchema(db);

        PaymentClient payment = new PaymentClient(env("PAYMENT_URL", "http://localhost:8002"), Duration.ofSeconds(2));
        CatalogClient catalog = new CatalogClient(env("CATALOG_URL", "http://localhost:8001"));
        RegistrationService service = new RegistrationService(db, catalog, payment,
                Duration.ofMinutes(Long.parseLong(env("HOLD_MINUTES", "10"))));
        OutboxRelay relay = new OutboxRelay(db, env("RABBITMQ_HOST", "localhost"),
                env("RABBITMQ_USER", null), env("RABBITMQ_PASSWORD", null));

        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(2);
        jobs.scheduleWithFixedDelay(relay::publishPending, 1000, 500, TimeUnit.MILLISECONDS);
        jobs.scheduleWithFixedDelay(() -> {
            try {
                int n = service.expireHolds();
                if (n > 0) Log.info("[expiration-job] " + n + " reserva(s) vencida(s) liberada(s)");
            } catch (Exception e) {
                Log.info("[expiration-job] falhou: " + e.getMessage());
            }
        }, 10, 10, TimeUnit.SECONDS);

        Main app = new Main(service, new Jwt(env("JWT_SECRET", null)), payment);
        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 512);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        Log.info("Registration Service ouvindo na porta " + port);
    }

    // ------------------------------------------------------------------ roteamento

    private void handle(HttpExchange ex) {
        String corr = header(ex, "X-Correlation-Id");
        if (corr == null || corr.isBlank()) corr = UUID.randomUUID().toString();
        ex.getResponseHeaders().add("X-Correlation-Id", corr);
        try {
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            Matcher reg = REG_ID.matcher(path), seats = SEATS.matcher(path);

            if (method.equals("POST") && path.equals("/registrations")) {
                Jwt.User user = jwt.verify(header(ex, "Authorization"));
                JsonNode body = readJson(ex);
                String activityId = body.path("activityId").asText(null);
                if (activityId == null) throw new ApiException(400, "BAD_REQUEST", "activityId obrigatorio");
                Map<String, Object> r = service.register(user.id(), activityId, body.path("cardToken").asText(null),
                        header(ex, "Idempotency-Key"), corr);
                send(ex, statusFor(r), r);
            } else if (method.equals("GET") && reg.matches()) {
                Jwt.User user = jwt.verify(header(ex, "Authorization"));
                send(ex, 200, service.findOwned(UUID.fromString(reg.group(1)), user.id()));
            } else if (method.equals("DELETE") && reg.matches()) {
                Jwt.User user = jwt.verify(header(ex, "Authorization"));
                send(ex, 200, service.cancel(UUID.fromString(reg.group(1)), user.id(), corr));
            } else if (method.equals("GET") && path.equals("/me/registrations")) {
                Jwt.User user = jwt.verify(header(ex, "Authorization"));
                send(ex, 200, service.listByParticipant(user.id()));
            } else if (method.equals("GET") && seats.matches()) {
                send(ex, 200, service.seats(seats.group(1)));
            } else if (method.equals("GET") && path.equals("/internal/circuit-breaker")) {
                send(ex, 200, Map.of("payment", payment.breaker.state().name()));
            } else if (method.equals("GET") && path.equals("/health")) {
                send(ex, 200, Map.of("status", "UP"));
            } else {
                send(ex, 404, Map.of("error", "NOT_FOUND", "message", "rota inexistente"));
            }
        } catch (ApiException e) {
            send(ex, e.status, Map.of("error", e.code, "message", e.getMessage()));
        } catch (Exception e) {
            Log.corr(corr, "erro inesperado: " + e);
            send(ex, 500, Map.of("error", "INTERNAL", "message", String.valueOf(e.getMessage())));
        }
    }

    private static int statusFor(Map<String, Object> r) {
        return switch ((String) r.get("status")) {
            case "CONFIRMED" -> 201;
            case "PAYMENT_FAILED" -> "DECLINED".equals(r.get("failureReason")) ? 402 : 503;
            default -> 202;
        };
    }

    // ------------------------------------------------------------------ utilitarios

    private static JsonNode readJson(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            return bytes.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(bytes);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "JSON invalido");
        }
    }

    private static void send(HttpExchange ex, int status, Object body) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (Exception ignored) {
        } finally {
            ex.close();
        }
    }

    private static String header(HttpExchange ex, String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        if (fallback == null) throw new IllegalStateException("variavel de ambiente obrigatoria: " + name);
        return fallback;
    }

    private static void runSchema(Db db) throws Exception {
        String sql;
        try (InputStream in = Main.class.getResourceAsStream("/schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        db.tx(c -> {
            for (String stmt : sql.split(";")) {
                String s = stmt.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim();
                if (!s.isEmpty()) Db.update(c, s);
            }
            return null;
        });
        Log.info("schema verificado");
    }
}
