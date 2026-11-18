package com.gkcontas.gateway;

import com.github.tomakehurst.wiremock.client.WireMock;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/** The internal services the dashboard reads, as WireMock stubs. */
public final class Stubs {

    private final WireMock wm;

    public Stubs(WireMock wm) {
        this.wm = wm;
    }

    public void profile(String userId, int delayMs) {
        wm.register(get(urlPathEqualTo("/users-service/v1/users/" + userId))
                .willReturn(okJson("""
                        {"id":"%s","name":"Ana Souza","email":"ana@example.com","tier":"gold"}
                        """.formatted(userId)).withFixedDelay(delayMs)));
    }

    public void profileStatus(String userId, int status) {
        wm.register(get(urlPathEqualTo("/users-service/v1/users/" + userId))
                .willReturn(aResponse().withStatus(status)));
    }

    /** {@code orders} orders with {@code linesPerOrder} lines each, product ids from 1 to {@code distinctProducts}. */
    public void orders(String userId, int delayMs, int orders, int linesPerOrder, int distinctProducts) {
        String body = IntStream.range(0, orders).mapToObj(o -> {
            String lines = IntStream.range(0, linesPerOrder)
                    .mapToObj(l -> "{\"productId\":%d,\"quantity\":%d}"
                            .formatted((o * linesPerOrder + l) % distinctProducts + 1, l + 1))
                    .collect(Collectors.joining(","));
            return "{\"id\":\"o-%d\",\"status\":\"DELIVERED\",\"total\":%d.90,\"items\":[%s]}"
                    .formatted(o + 1, 10 + o, lines);
        }).collect(Collectors.joining(",", "[", "]"));
        wm.register(get(urlPathEqualTo("/orders-service/v1/orders")).withQueryParam("userId", equalTo(userId))
                .willReturn(okJson(body).withFixedDelay(delayMs)));
    }

    public void ordersStatus(int status) {
        wm.register(get(urlPathEqualTo("/orders-service/v1/orders")).willReturn(aResponse().withStatus(status)));
    }

    public void recommendations(String userId, int delayMs) {
        wm.register(get(urlPathEqualTo("/recommendations-service/v1/recommendations"))
                .withQueryParam("userId", equalTo(userId))
                .willReturn(okJson("[{\"productId\":7,\"score\":0.93},{\"productId\":3,\"score\":0.71}]")
                        .withFixedDelay(delayMs)));
    }

    /** Per-item and batch endpoints for products 1..count, named "Product n". */
    public void catalog(int count, int delayMs) {
        for (int id = 1; id <= count; id++) {
            wm.register(get(urlPathEqualTo("/catalog-service/v1/products/" + id))
                    .willReturn(okJson(product(id)).withFixedDelay(delayMs)));
        }
        String all = IntStream.rangeClosed(1, count).mapToObj(Stubs::product)
                .collect(Collectors.joining(",", "[", "]"));
        wm.register(get(urlPathEqualTo("/catalog-service/v1/products")).withQueryParam("ids", matching(".+"))
                .willReturn(okJson(all).withFixedDelay(delayMs)));
    }

    public void catalogStatus(int status) {
        wm.register(get(urlPathMatching("/catalog-service/v1/products.*")).willReturn(aResponse().withStatus(status)));
    }

    public void dashboard(String userId, int delayMs) {
        profile(userId, delayMs);
        orders(userId, delayMs, 2, 2, 4);
        recommendations(userId, delayMs);
        catalog(4, 0);
    }

    public static String product(int id) {
        return "{\"id\":%d,\"name\":\"Product %d\",\"price\":%d.50}".formatted(id, id, id);
    }

    public static List<String> header(com.github.tomakehurst.wiremock.verification.LoggedRequest request, String name) {
        return request.getHeaders().getHeader(name).isPresent()
                ? request.getHeaders().getHeader(name).values()
                : List.of();
    }
}
