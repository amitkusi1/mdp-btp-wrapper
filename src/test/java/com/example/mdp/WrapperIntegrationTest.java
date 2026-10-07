package com.example.mdp;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultDestinationLoader;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationLoader;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WrapperIntegrationTest {
    private static final RSAKey KEY;
    private static final HttpServer BACKEND;
    private static final String ISSUER;
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static final AtomicReference<String> LAST_URI = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();
    private static final AtomicReference<String> LAST_COOKIE = new AtomicReference<>();
    private static final DestinationLoader ORIGINAL_LOADER = DestinationAccessor.getLoader();

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            BACKEND = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ISSUER = "http://127.0.0.1:" + BACKEND.getAddress().getPort();
            BACKEND.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            BACKEND.createContext("/sap/service/", exchange -> {
                BACKEND_CALLS.incrementAndGet();
                LAST_URI.set(exchange.getRequestURI().toString());
                LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                LAST_COOKIE.set(exchange.getRequestHeaders().getFirst("Cookie"));
                int status = 200;
                if (exchange.getRequestURI().getPath().endsWith("/redirect")) {
                    status = 302;
                    exchange.getResponseHeaders().set("Location", ISSUER + "/sap/service/secret");
                }
                if (exchange.getRequestURI().getPath().endsWith("/missing")) {
                    status = 404;
                }
                byte[] body = "<metadata/>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.getResponseHeaders().set("ETag", "\"test-etag\"");
                exchange.getResponseHeaders().set("Set-Cookie", "backend-secret=hidden");
                exchange.sendResponseHeaders(status, exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
                if (!exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            BACKEND.start();
            DestinationAccessor.setLoader(new DefaultDestinationLoader().registerDestination(
                    DefaultHttpDestination.builder(ISSUER).name("test-s4").build()));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER + "/jwks");
        properties.add("wrapper.destination-name", () -> "test-s4");
        properties.add("wrapper.service-path", () -> "/sap/service");
    }

    @AfterAll
    static void cleanup() {
        DestinationAccessor.setLoader(ORIGINAL_LOADER);
        BACKEND.stop(0);
    }

    @Test
    void healthIsPublicButODataRequiresToken() throws Exception {
        assertThat(call("GET", "/health", null).statusCode()).isEqualTo(200);
        int callsBefore = BACKEND_CALLS.get();
        assertThat(call("GET", "/odata/$metadata", null).statusCode()).isEqualTo(401);
        assertThat(BACKEND_CALLS.get()).isEqualTo(callsBefore);
    }

    @Test
    void validTokenProxiesMetadataWithoutLeakingClientCredentialsOrCookies() throws Exception {
        var response = call("GET", "/odata/$metadata?$format=xml&$top=1", validToken());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("<metadata/>");
        assertThat(response.headers().firstValue("ETag")).contains("\"test-etag\"");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(LAST_URI.get()).isEqualTo("/sap/service/$metadata?$format=xml&$top=1");
        assertThat(LAST_AUTH.get()).isNull();
        assertThat(LAST_COOKIE.get()).isNull();
    }

    @Test
    void rejectsWrongAudienceIssuerExpiryAndSignature() throws Exception {
        int callsBefore = BACKEND_CALLS.get();
        assertThat(call("GET", "/odata/", token(KEY, ISSUER, "another-api", "odata.read", 300))
                .statusCode()).isEqualTo(401);
        assertThat(call("GET", "/odata/", token(KEY, "https://wrong-issuer.test", "urn:mdp:btp-wrapper", "odata.read", 300))
                .statusCode()).isEqualTo(401);
        assertThat(call("GET", "/odata/", token(KEY, ISSUER, "urn:mdp:btp-wrapper", "odata.read", -120))
                .statusCode()).isEqualTo(401);
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        assertThat(call("GET", "/odata/", token(otherKey, ISSUER, "urn:mdp:btp-wrapper", "odata.read", 300))
                .statusCode()).isEqualTo(401);
        assertThat(BACKEND_CALLS.get()).isEqualTo(callsBefore);
    }

    @Test
    void requiresReadScopeAndDisallowsWrites() throws Exception {
        int callsBefore = BACKEND_CALLS.get();
        assertThat(call("GET", "/odata/", token(KEY, ISSUER, "urn:mdp:btp-wrapper", "other", 300))
                .statusCode()).isEqualTo(403);
        assertThat(call("POST", "/odata/", validToken()).statusCode()).isEqualTo(403);
        assertThat(BACKEND_CALLS.get()).isEqualTo(callsBefore);
    }

    @Test
    void testEndpointDoesNotCallSap() throws Exception {
        int callsBefore = BACKEND_CALLS.get();
        assertThat(call("GET", "/test", validToken()).statusCode()).isEqualTo(200);
        assertThat(BACKEND_CALLS.get()).isEqualTo(callsBefore);
    }

    @Test
    void doesNotFollowBackendRedirects() throws Exception {
        int callsBefore = BACKEND_CALLS.get();
        var response = call("GET", "/odata/redirect", validToken());
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(BACKEND_CALLS.get()).isEqualTo(callsBefore + 1);
    }

    @Test
    void preservesBackendNotFoundAndSupportsHead() throws Exception {
        assertThat(call("GET", "/odata/missing", validToken()).statusCode()).isEqualTo(404);
        var response = call("HEAD", "/odata/$metadata", validToken());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Cookie", "client-secret=hidden")
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String validToken() throws Exception {
        return token(KEY, ISSUER, "urn:mdp:btp-wrapper", "odata.read", 300);
    }

    private static String token(RSAKey key, String issuer, String audience, String scope, long expiresIn)
            throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("internal-test-client")
                .audience(audience).claim("scope", scope).issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(Instant.now().plusSeconds(expiresIn))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}