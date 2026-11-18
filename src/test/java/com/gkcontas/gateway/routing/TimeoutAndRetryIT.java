package com.gkcontas.gateway.routing;

import com.gkcontas.gateway.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.gkcontas.gateway.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;

class TimeoutAndRetryIT extends IntegrationTestBase {

    @Test
    void theDefaultRouteTimeoutAnswers504AndATimedOutGetIsNotRetried() {
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42"))
                .willReturn(okJson("{}").withFixedDelay(2500)));

        long start = System.nanoTime();
        client().get().uri("/api/users/42").header(HttpHeaders.AUTHORIZATION, bearer("42"))
                .exchange().expectStatus().isEqualTo(504);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).isBetween(900L, 2000L);
        DOWNSTREAM.verifyThat(1, getRequestedFor(urlPathEqualTo("/users-service/v1/users/42")));
    }

    @Test
    void theReportsRouteHasItsOwnLongerTimeout() {
        DOWNSTREAM.register(get(urlPathEqualTo("/reports-service/v1/reports/sales"))
                .willReturn(okJson("{\"total\":10}").withFixedDelay(1500)));

        client().get().uri("/api/reports/sales").header(HttpHeaders.AUTHORIZATION, bearer("7", "analyst"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void aGetThatGets503IsRetriedAndSucceeds() {
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42")).inScenario("flaky")
                .whenScenarioStateIs(STARTED).willSetStateTo("recovered")
                .willReturn(aResponse().withStatus(503)));
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42")).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"id\":\"42\"}")));

        client().get().uri("/api/users/42").header(HttpHeaders.AUTHORIZATION, bearer("42"))
                .exchange().expectStatus().isOk();

        DOWNSTREAM.verifyThat(2, getRequestedFor(urlPathEqualTo("/users-service/v1/users/42")));
    }

    @Test
    void aGetThatKeepsFailingGivesUpAfterTwoRetries() {
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42")).willReturn(aResponse().withStatus(503)));

        client().get().uri("/api/users/42").header(HttpHeaders.AUTHORIZATION, bearer("42"))
                .exchange().expectStatus().isEqualTo(503);

        DOWNSTREAM.verifyThat(3, getRequestedFor(urlPathEqualTo("/users-service/v1/users/42")));
    }

    @Test
    void a500IsNotRetried() {
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42")).willReturn(aResponse().withStatus(500)));

        client().get().uri("/api/users/42").header(HttpHeaders.AUTHORIZATION, bearer("42"))
                .exchange().expectStatus().isEqualTo(500);

        DOWNSTREAM.verifyThat(1, getRequestedFor(urlPathEqualTo("/users-service/v1/users/42")));
    }

    @Test
    void aPostThatGets503IsNeverRetried() {
        DOWNSTREAM.register(post(urlPathEqualTo("/orders-service/v1/orders/")).willReturn(aResponse().withStatus(503)));

        client().post().uri("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer("42"))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"productId\":7,\"quantity\":1}")
                .exchange().expectStatus().isEqualTo(503);

        DOWNSTREAM.verifyThat(1, postRequestedFor(urlPathEqualTo("/orders-service/v1/orders/")));
    }
}
