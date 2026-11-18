package com.gkcontas.gateway.web;

import com.gkcontas.gateway.IntegrationTestBase;
import com.gkcontas.gateway.Stubs;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.gkcontas.gateway.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIT extends IntegrationTestBase {

    @Test
    void generatesAnIdAndUsesTheSameOneDownstreamAndInTheResponse() {
        DOWNSTREAM.register(get(urlPathEqualTo("/catalog-service/v1/products/7")).willReturn(okJson("{}")));

        String returned = client().get().uri("/api/catalog/7").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Request-Id");

        assertThat(returned).hasSize(36);
        LoggedRequest seen = DOWNSTREAM.find(getRequestedFor(urlPathEqualTo("/catalog-service/v1/products/7"))).getFirst();
        assertThat(Stubs.header(seen, "X-Request-Id")).containsExactly(returned);
    }

    @Test
    void keepsASafeInboundIdAndReplacesAnUnsafeOne() {
        DOWNSTREAM.register(get(urlPathEqualTo("/catalog-service/v1/products/7")).willReturn(okJson("{}")));

        client().get().uri("/api/catalog/7").header("X-Request-Id", "mobile-app-0001").exchange()
                .expectHeader().valueEquals("X-Request-Id", "mobile-app-0001");
        String replaced = client().get().uri("/api/catalog/7").header("X-Request-Id", "x").exchange()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Request-Id");

        assertThat(replaced).isNotEqualTo("x").hasSize(36);
    }

    @Test
    void errorResponsesWrittenByTheFiltersCarryTheIdToo() {
        client().get().uri("/api/orders/1").header("X-Request-Id", "support-ticket-77").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Request-Id", "support-ticket-77");
    }

    @Test
    void everyCallOfTheBffFanOutCarriesTheSameIdAndUser() {
        new Stubs(DOWNSTREAM).dashboard("42", 0);

        client().get().uri("/bff/dashboard")
                .header(HttpHeaders.AUTHORIZATION, bearer("42", "customer"))
                .header("X-Request-Id", "dashboard-req-0001")
                .exchange().expectStatus().isOk();

        List<LoggedRequest> calls = DOWNSTREAM.find(getRequestedFor(anyUrl()));
        assertThat(calls).hasSize(4);
        assertThat(calls).allSatisfy(call -> {
            assertThat(Stubs.header(call, "X-Request-Id")).containsExactly("dashboard-req-0001");
            assertThat(Stubs.header(call, "X-User-Id")).containsExactly("42");
            assertThat(call.containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
        });
    }
}
