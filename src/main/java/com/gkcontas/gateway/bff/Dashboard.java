package com.gkcontas.gateway.bff;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the dashboard screen needs, in one payload. {@code degraded} names the
 * parts that came back empty because their service failed or was too slow -
 * the client can tell an empty list from a missing one.
 */
public record Dashboard(
        Downstream.UserProfile profile,
        List<OrderView> orders,
        List<Downstream.Recommendation> recommendations,
        List<String> degraded) {

    public record OrderView(String id, String status, BigDecimal total, List<OrderLine> lines) {
    }

    /** {@code productName} is null when the catalog could not be reached. */
    public record OrderLine(long productId, int quantity, String productName) {
    }
}
