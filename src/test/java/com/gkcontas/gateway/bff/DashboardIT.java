package com.gkcontas.gateway.bff;

import com.gkcontas.gateway.IntegrationTestBase;
import com.gkcontas.gateway.Stubs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.gkcontas.gateway.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;

class DashboardIT extends IntegrationTestBase {

    private final Stubs stubs = new Stubs(DOWNSTREAM);

    private WebTestClient.ResponseSpec dashboard(String query) {
        return client().get().uri("/bff/dashboard" + query)
                .header(HttpHeaders.AUTHORIZATION, bearer("42", "customer"))
                .exchange();
    }

    private long timed(String query) {
        long start = System.nanoTime();
        dashboard(query).expectStatus().isOk();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void aggregatesTheFourServicesIntoOnePayload() {
        stubs.dashboard("42", 0);

        dashboard("").expectStatus().isOk().expectBody()
                .jsonPath("$.profile.name").isEqualTo("Ana Souza")
                .jsonPath("$.orders.length()").isEqualTo(2)
                .jsonPath("$.orders[0].lines[0].productName").isEqualTo("Product 1")
                .jsonPath("$.orders[1].lines[1].productName").isEqualTo("Product 4")
                .jsonPath("$.recommendations[0].productId").isEqualTo(7)
                .jsonPath("$.degraded.length()").isEqualTo(0);
    }

    @Test
    void parallelWaitsForTheSlowestCallAndSequentialForTheSum() {
        stubs.dashboard("42", 200);
        timed("");

        long parallel = timed("?strategy=parallel");
        long sequential = timed("?strategy=sequential");

        assertThat(parallel).isLessThan(450);
        assertThat(sequential).isGreaterThanOrEqualTo(600);
    }

    @Test
    void aSlowOptionalServiceCostsItsTimeoutNotItsLatency() {
        stubs.profile("42", 0);
        stubs.orders("42", 0, 1, 1, 1);
        stubs.catalog(1, 0);
        stubs.recommendations("42", 3000);

        long start = System.nanoTime();
        dashboard("").expectStatus().isOk().expectBody()
                .jsonPath("$.recommendations.length()").isEqualTo(0)
                .jsonPath("$.degraded[0]").isEqualTo("recommendations")
                .jsonPath("$.orders.length()").isEqualTo(1);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
    }

    @Test
    void failedOrdersDegradeTheOrdersPart() {
        stubs.profile("42", 0);
        stubs.ordersStatus(503);
        stubs.recommendations("42", 0);

        dashboard("").expectStatus().isOk().expectBody()
                .jsonPath("$.orders.length()").isEqualTo(0)
                .jsonPath("$.degraded[0]").isEqualTo("orders")
                .jsonPath("$.recommendations.length()").isEqualTo(2);
    }

    @Test
    void aFailedCatalogKeepsTheOrdersWithoutProductNames() {
        stubs.profile("42", 0);
        stubs.orders("42", 0, 1, 2, 2);
        stubs.recommendations("42", 0);
        stubs.catalogStatus(503);

        dashboard("").expectStatus().isOk().expectBody()
                .jsonPath("$.orders[0].lines.length()").isEqualTo(2)
                .jsonPath("$.orders[0].lines[0].productName").doesNotExist()
                .jsonPath("$.degraded[0]").isEqualTo("catalog");
    }

    @Test
    void withoutTheProfileThereIsNoDashboard() {
        stubs.profileStatus("42", 503);
        stubs.orders("42", 0, 1, 1, 1);
        stubs.recommendations("42", 0);
        dashboard("").expectStatus().isEqualTo(502).expectBody()
                .jsonPath("$.message").isEqualTo("User profile service is unavailable");

        stubs.profileStatus("42", 404);
        dashboard("").expectStatus().isNotFound().expectBody()
                .jsonPath("$.message").isEqualTo("User profile not found");
    }

    @Test
    void aSlowProfileIsA504() {
        stubs.profile("42", 2000);
        stubs.orders("42", 0, 1, 1, 1);
        stubs.recommendations("42", 0);

        dashboard("").expectStatus().isEqualTo(504).expectBody()
                .jsonPath("$.message").isEqualTo("User profile service timed out");
    }

    @Test
    void perItemEnrichmentMakesOneCallPerLineAndBatchOnlyOne() {
        stubs.profile("42", 0);
        stubs.orders("42", 0, 5, 4, 12);
        stubs.recommendations("42", 0);
        stubs.catalog(12, 0);

        dashboard("?enrichment=per-item").expectStatus().isOk().expectBody()
                .jsonPath("$.orders[4].lines[3].productName").isEqualTo("Product 8");
        assertThat(DOWNSTREAM.find(getRequestedFor(urlPathMatching("/catalog-service/v1/products/\\d+")))).hasSize(20);

        DOWNSTREAM.resetRequests();
        dashboard("?enrichment=batch").expectStatus().isOk().expectBody()
                .jsonPath("$.orders[4].lines[3].productName").isEqualTo("Product 8");
        var batch = DOWNSTREAM.find(getRequestedFor(urlPathEqualTo("/catalog-service/v1/products")));
        assertThat(batch).hasSize(1);
        assertThat(batch.getFirst().queryParameter("ids").firstValue().split(",")).hasSize(12);
    }

    @Test
    void batchEnrichmentSplitsLongIdListsIntoChunks() {
        stubs.profile("42", 0);
        stubs.orders("42", 0, 30, 4, 120);
        stubs.recommendations("42", 0);
        String all = IntStream.rangeClosed(1, 120).mapToObj(Stubs::product).collect(Collectors.joining(",", "[", "]"));
        DOWNSTREAM.register(get(urlPathEqualTo("/catalog-service/v1/products")).willReturn(okJson(all)));

        dashboard("").expectStatus().isOk().expectBody().jsonPath("$.degraded.length()").isEqualTo(0);

        var calls = DOWNSTREAM.find(getRequestedFor(urlPathEqualTo("/catalog-service/v1/products")));
        assertThat(calls).hasSize(3);
        assertThat(calls).allSatisfy(c ->
                assertThat(c.queryParameter("ids").firstValue().split(",").length).isLessThanOrEqualTo(50));
    }

    @Test
    void rejectsAnUnknownStrategy() {
        dashboard("?strategy=eager").expectStatus().isBadRequest().expectBody()
                .jsonPath("$.message").isEqualTo("strategy must be one of: parallel, sequential");
        dashboard("?enrichment=lazy").expectStatus().isBadRequest().expectBody()
                .jsonPath("$.message").isEqualTo("enrichment must be one of: batch, per-item");
    }

    @Test
    void theDashboardRequiresAToken() {
        client().get().uri("/bff/dashboard").exchange().expectStatus().isUnauthorized();
        assertThat(DOWNSTREAM.find(anyRequestedFor(anyUrl()))).isEmpty();
    }
}
