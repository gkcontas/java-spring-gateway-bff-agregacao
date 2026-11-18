package com.gkcontas.gateway.routing;

import com.gkcontas.gateway.IntegrationTestBase;
import com.gkcontas.gateway.Stubs;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.gkcontas.gateway.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;

class IdentityHeadersIT extends IntegrationTestBase {

    private LoggedRequest only(String path) {
        var requests = DOWNSTREAM.find(getRequestedFor(urlPathEqualTo(path)));
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    @Test
    void aSpoofedUserIdIsReplacedByTheOneFromTheToken() {
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42")).willReturn(okJson("{}")));

        client().get().uri("/api/users/42")
                .header(HttpHeaders.AUTHORIZATION, bearer("42", "customer"))
                .header("X-User-Id", "1")
                .header("X-User-Roles", "admin")
                .exchange().expectStatus().isOk();

        LoggedRequest seen = only("/users-service/v1/users/42");
        assertThat(Stubs.header(seen, "X-User-Id")).containsExactly("42");
        assertThat(Stubs.header(seen, "X-User-Roles")).containsExactly("customer");
    }

    @Test
    void spoofedIdentityHeadersAreRemovedOnPublicRoutesToo() {
        DOWNSTREAM.register(get(urlPathEqualTo("/catalog-service/v1/products/7")).willReturn(okJson("{}")));

        client().get().uri("/api/catalog/7")
                .header("X-User-Id", "1")
                .header("X-User-Roles", "admin")
                .exchange().expectStatus().isOk();

        LoggedRequest seen = only("/catalog-service/v1/products/7");
        assertThat(Stubs.header(seen, "X-User-Id")).isEmpty();
        assertThat(Stubs.header(seen, "X-User-Roles")).isEmpty();
    }

    @Test
    void theBearerTokenStaysAtTheGateway() {
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42")).willReturn(okJson("{}")));

        client().get().uri("/api/users/42").header(HttpHeaders.AUTHORIZATION, bearer("42"))
                .exchange().expectStatus().isOk();

        assertThat(only("/users-service/v1/users/42").containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
    }
}
