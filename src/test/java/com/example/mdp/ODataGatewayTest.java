package com.example.mdp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ODataGatewayTest {
    @Test
    void appendsServicePathAndPreservesRawODataQuery() {
        assertThat(ODataGateway.targetUri(URI.create("https://virtual-host:443/"), "/sap/service",
                "/Products('A')", "$filter=Name%20eq%20%27Test%27&$top=1").toString())
                .isEqualTo("https://virtual-host:443/sap/service/Products('A')?$filter=Name%20eq%20%27Test%27&$top=1");
    }

    @Test
    void doesNotDuplicateServicePathInDestination() {
        assertThat(ODataGateway.targetUri(URI.create("https://virtual-host/sap/service/"),
                "/sap/service", "/$metadata", null).toString())
                .isEqualTo("https://virtual-host/sap/service/$metadata");
    }

    @Test
    void serviceRootHasTrailingSlash() {
        assertThat(ODataGateway.targetUri(URI.create("https://virtual-host"), "/sap/service", "", null)
                .getPath()).isEqualTo("/sap/service/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/../admin", "/%2e%2e/admin", "/%252e%252e/admin", "//evil.test",
            "/%5cadmin", "/a/./b", "https://evil.test", "/a%0db", "/a%3fb"})
    void rejectsPathsThatCouldEscapeServiceRoot(String path) {
        assertThatThrownBy(() -> ODataGateway.targetUri(URI.create("https://virtual-host"),
                "/sap/service", path, null)).isInstanceOf(IllegalArgumentException.class);
    }
}