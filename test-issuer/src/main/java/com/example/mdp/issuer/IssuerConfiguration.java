package com.example.mdp.issuer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ClientCredentialsAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class IssuerConfiguration {
    private static final List<String> ALLOWED_AUDIENCES = List.of("urn:mdp:btp-wrapper", "urn:mdp:other-api");

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http) throws Exception {
        var authorizationServer = OAuth2AuthorizationServerConfigurer.authorizationServer();
        var standardConverter = new OAuth2ClientCredentialsAuthenticationConverter();
        return http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server.tokenEndpoint(endpoint ->
                        endpoint.accessTokenRequestConverter(request -> {
                            if (!AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(request.getParameter("grant_type"))) {
                                return null;
                            }
                            String[] audiences = request.getParameterValues("audience");
                            if (audiences == null || audiences.length != 1 || audiences[0].isBlank()) {
                                throw oauthError(OAuth2ErrorCodes.INVALID_REQUEST, "Exactly one audience is required");
                            }
                            return standardConverter.convert(request);
                        })))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain otherEndpoints(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    RegisteredClientRepository clients(@Value("${test-issuer.client-id}") String clientId,
                                       @Value("${test-issuer.client-secret}") String clientSecret) {
        if (clientId.isBlank() || clientSecret.length() < 32
                || clientSecret.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Configure a client ID and a random client secret of 32 characters to 72 UTF-8 bytes");
        }
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var client = RegisteredClient.withId("internal-mdp-test-client")
                .clientId(clientId)
                .clientSecret(encoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("odata.read")
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build())
                .build();
        return new InMemoryRegisteredClientRepository(client);
    }

    @Bean
    AuthorizationServerSettings serverSettings(@Value("${test-issuer.issuer-uri}") String issuer) {
        URI uri = URI.create(issuer);
        boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))) {
            throw new IllegalArgumentException("Issuer must be an HTTPS origin; HTTP is permitted only for local testing");
        }
        return AuthorizationServerSettings.builder().issuer(issuer)
                .tokenEndpoint("/oauth/token")
                .jwkSetEndpoint("/.well-known/jwks.json")
                .build();
    }

    @Bean
    JWKSource<SecurityContext> signingKeys() throws JOSEException {
        var key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        var keys = new JWKSet(key);
        return (selector, context) -> selector.select(keys);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> audienceCustomizer() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                    && context.getAuthorizationGrant() instanceof OAuth2ClientCredentialsAuthenticationToken grant) {
                Object requestedAudience = grant.getAdditionalParameters().get("audience");
                if (!(requestedAudience instanceof String audience) || !ALLOWED_AUDIENCES.contains(audience)) {
                    throw oauthError("invalid_target", "Audience is not permitted for this client");
                }
                context.getClaims().audience(List.of(audience));
            }
        };
    }

    private static OAuth2AuthenticationException oauthError(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
    }
}