package com.gkcontas.gateway.routing;

import com.gkcontas.gateway.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.gkcontas.gateway.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;

class RoutingIT extends IntegrationTestBase {

    @Test
    void rewritesThePublicPathToTheInternalOneAndKeepsTheQueryString() {
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42"))
                .willReturn(okJson("{\"id\":\"42\",\"name\":\"Ana\"}")));

        client().get().uri("/api/users/42?fields=name")
                .header(HttpHeaders.AUTHORIZATION, bearer("42"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Ana");

        DOWNSTREAM.verifyThat(1, getRequestedFor(urlEqualTo("/users-service/v1/users/42?fields=name")));
    }

    @Test
    void catalogIsPublic() {
        DOWNSTREAM.register(get(urlPathEqualTo("/catalog-service/v1/products/7"))
                .willReturn(okJson("{\"id\":7,\"name\":\"Kettle\"}")));

        client().get().uri("/api/catalog/7").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Kettle");
    }

    @Test
    void protectedRouteWithoutTokenIs401AndNeverReachesTheService() {
        client().get().uri("/api/orders/o-1").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody().jsonPath("$.message").isEqualTo("Missing bearer token");

        assertThat(DOWNSTREAM.find(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void anUnlistedPathIsProtectedByDefault() {
        client().get().uri("/api/unlisted/1").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/unlisted/1").header(HttpHeaders.AUTHORIZATION, bearer("42"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void reportsRequireTheAnalystRole() {
        DOWNSTREAM.register(get(urlPathEqualTo("/reports-service/v1/reports/sales"))
                .willReturn(okJson("{\"total\":10}")));

        client().get().uri("/api/reports/sales").header(HttpHeaders.AUTHORIZATION, bearer("42", "customer"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.message").isEqualTo("Role 'analyst' is required");
        client().get().uri("/api/reports/sales").header(HttpHeaders.AUTHORIZATION, bearer("7", "analyst"))
                .exchange()
                .expectStatus().isOk();

        DOWNSTREAM.verifyThat(1, getRequestedFor(urlPathEqualTo("/reports-service/v1/reports/sales")));
    }

    @Test
    void internalResponseHeadersDoNotLeakOut() {
        DOWNSTREAM.register(get(urlPathEqualTo("/catalog-service/v1/products/7"))
                .willReturn(okJson("{}").withHeader("X-Internal-Debug", "db=replica-3")));

        client().get().uri("/api/catalog/7").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("X-Internal-Debug");
    }

    @Test
    void aDotSegmentCannotWalkFromAPublicRouteToAProtectedOne() {
        client().get().uri(URI.create("http://localhost:" + port + "/api/catalog/../users/1")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").isEqualTo("Request path contains a disallowed sequence");
        client().get().uri(URI.create("http://localhost:" + port + "/api/catalog/%2e%2e/users/1")).exchange()
                .expectStatus().isBadRequest();

        assertThat(DOWNSTREAM.find(anyRequestedFor(anyUrl()))).isEmpty();
    }
}
