package com.example.mdp;

import com.sap.cloud.sdk.cloudplatform.connectivity.ApacheHttpClient5Accessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class ODataGateway {
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final List<String> REQUEST_HEADERS = List.of(
            "Accept", "If-None-Match", "If-Modified-Since", "DataServiceVersion", "MaxDataServiceVersion");
    private static final List<String> RESPONSE_HEADERS = List.of(
            "Content-Type", "ETag", "Last-Modified", "DataServiceVersion");
    private final String destinationName;
    private final String servicePath;

    public ODataGateway(@Value("${wrapper.destination-name}") String destinationName,
                        @Value("${wrapper.service-path}") String servicePath) {
        this.destinationName = destinationName;
        this.servicePath = servicePath;
    }

    public ResponseEntity<byte[]> read(String method, String suffix, String query, HttpHeaders headers)
            throws IOException {
        validateSuffix(suffix);
        var destination = DestinationAccessor.getDestination(destinationName).asHttp();
        var client = ApacheHttpClient5Accessor.getHttpClient(destination);
        var request = new HttpUriRequestBase(method, targetUri(destination.getUri(), servicePath, suffix, query));
        var context = HttpClientContext.create();
        context.setRequestConfig(RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setCookieSpec(StandardCookieSpec.IGNORE)
                .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                .setResponseTimeout(Timeout.ofSeconds(30))
                .build());
        REQUEST_HEADERS.forEach(name -> {
            if (headers.getFirst(name) != null) {
                request.setHeader(name, headers.getFirst(name));
            }
        });
        return client.execute(request, context, response -> {
            if (response.getCode() >= 300 && response.getCode() < 400 && response.getCode() != 304) {
                throw new IOException("Backend redirects are not supported");
            }
            var outgoing = new HttpHeaders();
            outgoing.setCacheControl("no-store");
            RESPONSE_HEADERS.forEach(name -> {
                if (response.getFirstHeader(name) != null) {
                    outgoing.set(name, response.getFirstHeader(name).getValue());
                }
            });
            byte[] body = new byte[0];
            if (response.getEntity() != null) {
                try (var stream = response.getEntity().getContent()) {
                    body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (body.length > MAX_RESPONSE_BYTES) {
                        request.cancel();
                        throw new IOException("Backend response exceeds limit");
                    }
                }
            }
            return ResponseEntity.status(response.getCode()).headers(outgoing).body(body);
        });
    }

    static URI targetUri(URI destination, String servicePath, String suffix, String query) {
        validateSuffix(suffix);
        if (destination.getRawQuery() != null || destination.getRawFragment() != null
                || destination.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Destination URL must not contain query, fragment or credentials");
        }
        String base = trimSlashes(destination.toString());
        if (destination.getRawPath() == null || destination.getRawPath().matches("/?")) {
            base += "/" + trimSlashes(servicePath);
        }
        return URI.create(base + (suffix.isEmpty() ? "/" : suffix) + (query == null ? "" : "?" + query));
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    private static void validateSuffix(String suffix) {
        String decoded = URLDecoder.decode(suffix.replace("+", "%2B"), StandardCharsets.UTF_8);
        if ((!suffix.isEmpty() && !suffix.startsWith("/")) || decoded.contains("\\")
                || decoded.contains("%") || decoded.contains("//") || decoded.contains("?")
                || decoded.contains("#") || decoded.chars().anyMatch(Character::isISOControl)
                || List.of(decoded.split("/", -1)).stream().anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("Invalid OData path");
        }
    }
}