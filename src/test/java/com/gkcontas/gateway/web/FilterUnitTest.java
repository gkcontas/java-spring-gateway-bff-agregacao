package com.gkcontas.gateway.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FilterUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc12345", "0f8c2a3e-1b2c-4d5e-8f90-123456789abc", "req-2026-11-18-0001"})
    void keepsASafeInboundRequestId(String id) {
        assertThat(CorrelationIdWebFilter.resolve(id)).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "id with spaces", "id\r\nX-Injected: yes", "id;drop", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void replacesAnUnsafeInboundRequestId(String id) {
        assertThat(CorrelationIdWebFilter.resolve(id)).isNotEqualTo(id).hasSize(36);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/catalog/../users/1", "/api/catalog/%2e%2e/users/1", "/api/catalog/..%2Fusers/1",
            "/api/catalog;x=1/1", "/api/catalog/%5c..", "/api/./users/1", "/api/users/1%00"})
    void refusesPathsThatReadDifferentlyDownstream(String path) {
        assertThat(PathGuardWebFilter.isDisallowed(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/catalog/1", "/api/users/42", "/bff/dashboard", "/api/catalog/a.b"})
    void acceptsOrdinaryPaths(String path) {
        assertThat(PathGuardWebFilter.isDisallowed(path)).isFalse();
    }
}
