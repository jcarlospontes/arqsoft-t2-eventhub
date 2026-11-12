package br.uece.eventhub.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Validacao de JWT HS256 emitido pelo Catalogo (segredo compartilhado via variavel de ambiente).
 * Recorte do prototipo; o desenho alvo usa Keycloak/OIDC com chaves assimetricas (ADR-05).
 */
final class Jwt {

    record User(String id, String role) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final byte[] secret;

    Jwt(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    User verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(401, "TOKEN_MISSING", "token ausente");
        }
        String[] parts = authorization.substring(7).split("\\.");
        if (parts.length != 3) throw new ApiException(401, "TOKEN_INVALID", "token invalido");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) {
                throw new ApiException(401, "TOKEN_INVALID", "assinatura invalida");
            }
            JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) {
                throw new ApiException(401, "TOKEN_INVALID", "algoritmo nao suportado");
            }
            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (claims.has("exp") && claims.get("exp").asLong() < Instant.now().getEpochSecond()) {
                throw new ApiException(401, "TOKEN_EXPIRED", "token expirado");
            }
            return new User(claims.path("sub").asText(), claims.path("role").asText());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(401, "TOKEN_INVALID", "token invalido");
        }
    }
}
