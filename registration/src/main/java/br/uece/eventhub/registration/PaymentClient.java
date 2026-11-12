package br.uece.eventhub.registration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Chamada sincrona REST ao Pagamento (Python), protegida por timeout + Circuit Breaker.
 * Qualquer falha tecnica vira {@link Unavailable}; quem chama compensa liberando a vaga.
 */
final class PaymentClient {

    static final class Unavailable extends RuntimeException {
        Unavailable(String msg) { super(msg); }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final String baseUrl;
    private final Duration timeout;
    final CircuitBreaker breaker = new CircuitBreaker("payment", 5, 15_000);

    PaymentClient(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
    }

    /** @return status final do pagamento: APPROVED ou DECLINED */
    String pay(UUID registrationId, double amount, String cardToken, String corr) {
        try {
            return breaker.call(() -> {
                try {
                    String body = MAPPER.writeValueAsString(Map.of(
                            "registration_id", registrationId.toString(),
                            "amount", amount,
                            "card_token", cardToken == null ? "" : cardToken));
                    HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/payments"))
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .header("X-Correlation-Id", corr)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
                    return MAPPER.readTree(resp.body()).get("status").asText();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e.getClass().getSimpleName(), e);
                }
            });
        } catch (CircuitBreaker.OpenException e) {
            throw new Unavailable(e.getMessage());
        } catch (RuntimeException e) {
            throw new Unavailable("pagamento falhou: " + e.getMessage());
        }
    }

    /** Compensacao best-effort: cancela/estorna o pagamento do qual a Inscricao desistiu. */
    void cancel(UUID registrationId, String corr) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/payments/" + registrationId + "/cancel"))
                    .timeout(Duration.ofSeconds(2))
                    .header("X-Correlation-Id", corr)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
            Log.corr(corr, "compensacao enviada ao Pagamento para " + registrationId);
        } catch (Exception e) {
            Log.corr(corr, "compensacao de pagamento falhou para " + registrationId + ": " + e);
        }
    }
}
