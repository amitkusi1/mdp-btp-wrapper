package com.example.mdp.issuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IssuerIntegrationTest {
    private static final String CLIENT_ID = "mdp-test-client";
    private static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String ISSUER = "http://localhost:9000";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("test-issuer.client-id", () -> CLIENT_ID);
        properties.add("test-issuer.client-secret", () -> SECRET);
        properties.add("test-issuer.issuer-uri", () -> ISSUER);
    }

    @Test
    void issuesVerifiableShortLivedTokenForWrapperAudience() throws Exception {
        var response = token("grant_type=client_credentials&audience=urn:mdp:btp-wrapper&scope=odata.read", SECRET);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode payload = mapper.readTree(response.body());
        assertThat(payload.path("token_type").asText()).isEqualTo("Bearer");
        assertThat(payload.path("expires_in").asLong()).isBetween(290L, 300L);
        assertThat(payload.has("refresh_token")).isFalse();
        var jwt = decoder().decode(payload.path("access_token").asText());
        assertThat(jwt.getAudience()).containsExactly("urn:mdp:btp-wrapper");
        assertThat(jwt.getClaimAsStringList("scope")).containsExactly("odata.read");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(jwt.getSubject()).isEqualTo(CLIENT_ID);
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
    }

    @Test
    void otherPermittedAudienceIsRejectedByWrapperValidationContract() throws Exception {
        var response = token("grant_type=client_credentials&audience=urn:mdp:other-api", SECRET);
        assertThat(response.statusCode()).isEqualTo(200);
        String accessToken = mapper.readTree(response.body()).path("access_token").asText();
        assertThatThrownBy(() -> decoder().decode(accessToken)).isInstanceOf(JwtValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "&audience=", "&audience=%20", "&audience=urn:mdp:btp-wrapper&audience=urn:mdp:other-api"})
    void rejectsMissingEmptyOrDuplicateAudience(String audience) throws Exception {
        assertError(token("grant_type=client_credentials" + audience, SECRET), 400, "invalid_request");
    }

    @Test
    void rejectsUnknownAudience() throws Exception {
        assertError(token("grant_type=client_credentials&audience=urn:unknown", SECRET), 400, "invalid_target");
    }

    @Test
    void rejectsIncorrectCredentials() throws Exception {
        assertError(token("grant_type=client_credentials&audience=urn:mdp:btp-wrapper", "incorrect-secret"),
                401, "invalid_client");
    }

    @Test
    void rejectsUnregisteredScope() throws Exception {
        assertError(token("grant_type=client_credentials&audience=urn:mdp:btp-wrapper&scope=admin", SECRET),
                400, "invalid_scope");
    }

    @Test
    void supportsClientSecretPost() throws Exception {
        var request = formRequest("grant_type=client_credentials&audience=urn:mdp:btp-wrapper"
                + "&client_id=" + CLIENT_ID + "&client_secret=" + SECRET);
        assertThat(send(request).statusCode()).isEqualTo(200);
    }

    @Test
    void doesNotAllowUnauthenticatedTokenRequests() throws Exception {
        assertThat(send(formRequest("grant_type=client_credentials&audience=urn:mdp:btp-wrapper"))
                .statusCode()).isEqualTo(401);
    }

    @Test
    void publishesOnlyPublicKeysAndFixedIssuerMetadata() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/.well-known/jwks.json")).GET());
        assertThat(response.statusCode()).isEqualTo(200);
        var key = mapper.readTree(response.body()).path("keys").get(0);
        assertThat(key.path("kty").asText()).isEqualTo("RSA");
        assertThat(key.has("n")).isTrue();
        assertThat(key.has("e")).isTrue();
        for (String privateField : List.of("d", "p", "q", "dp", "dq", "qi", "oth", "k")) {
            assertThat(key.has(privateField)).isFalse();
        }
        var metadata = send(HttpRequest.newBuilder(uri("/.well-known/oauth-authorization-server")).GET());
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(metadata.body()).path("issuer").asText()).isEqualTo(ISSUER);
        assertThat(send(HttpRequest.newBuilder(uri("/health")).GET()).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsUnsupportedGrant() throws Exception {
        assertError(token("grant_type=password&audience=urn:mdp:btp-wrapper", SECRET),
                400, "unsupported_grant_type");
    }

    private NimbusJwtDecoder decoder() {
        var decoder = NimbusJwtDecoder.withJwkSetUri(uri("/.well-known/jwks.json").toString()).build();
        var audience = new JwtClaimValidator<List<String>>("aud",
                audiences -> audiences != null && audiences.contains("urn:mdp:btp-wrapper"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), audience));
        return decoder;
    }

    private HttpResponse<String> token(String form, String secret) throws Exception {
        String credentials = Base64.getEncoder().encodeToString((CLIENT_ID + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return send(formRequest(form).header("Authorization", "Basic " + credentials));
    }

    private HttpRequest.Builder formRequest(String form) {
        return HttpRequest.newBuilder(uri("/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertError(HttpResponse<String> response, int status, String error) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = mapper.readTree(response.body());
        assertThat(body.path("error").asText()).isEqualTo(error);
        assertThat(body.has("access_token")).isFalse();
    }
}