package br.uece.eventhub.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Chamada sincrona REST ao Catalogo (Python) para obter capacidade e preco da atividade. */
final class CatalogClient {

    record Activity(String id, int capacity, double price) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final String baseUrl;

    CatalogClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    Activity getActivity(String activityId, String corr) {
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/activities/" + activityId))
                    .timeout(Duration.ofSeconds(2))
                    .header("X-Correlation-Id", corr)
                    .GET().build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ApiException(503, "CATALOG_UNAVAILABLE", "catalogo indisponivel");
        }
        if (resp.statusCode() == 404) throw new ApiException(404, "ACTIVITY_NOT_FOUND", "atividade nao encontrada");
        if (resp.statusCode() != 200) throw new ApiException(503, "CATALOG_UNAVAILABLE", "catalogo respondeu " + resp.statusCode());
        try {
            JsonNode b = MAPPER.readTree(resp.body());
            return new Activity(b.get("id").asText(), b.get("capacity").asInt(), b.get("price").asDouble());
        } catch (Exception e) {
            throw new ApiException(502, "CATALOG_BAD_RESPONSE", "resposta invalida do catalogo");
        }
    }
}
