package com.gkcontas.gateway.bff;

import java.math.BigDecimal;
import java.util.List;

/** Payloads of the internal services, as the BFF reads them. */
public final class Downstream {

    private Downstream() {
    }

    public record UserProfile(String id, String name, String email, String tier) {
    }

    public record Order(String id, String status, BigDecimal total, List<OrderItem> items) {
        public Order {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record OrderItem(long productId, int quantity) {
    }

    public record Product(long id, String name, BigDecimal price) {
    }

    public record Recommendation(long productId, double score) {
    }
}
