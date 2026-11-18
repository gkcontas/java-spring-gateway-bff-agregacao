package com.gkcontas.gateway.routing;

import com.gkcontas.gateway.config.DownstreamProperties;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory.RetryConfig;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

/**
 * The public API is {@code /api/<resource>/**}; each internal service has its
 * own prefix and version. The rewrite keeps that layout out of the clients.
 */
@Configuration
public class RouteConfig {

    static final String INTERNAL_DEBUG_HEADER = "X-Internal-Debug";

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, DownstreamProperties downstream, RouteProperties properties) {
        String base = downstream.baseUrl().toString();
        return builder.routes()
                .route("users", r -> r.path("/api/users/**")
                        .filters(f -> f
                                .rewritePath("/api/users/?(?<rest>.*)", "/users-service/v1/users/${rest}")
                                .removeResponseHeader(INTERNAL_DEBUG_HEADER)
                                .retry(c -> idempotentOnly(c, properties.getRetries())))
                        .uri(base))
                .route("orders", r -> r.path("/api/orders/**")
                        // No retry: the route accepts POST, and a POST repeated
                        // after a 503 is a second order.
                        .filters(f -> f
                                .rewritePath("/api/orders/?(?<rest>.*)", "/orders-service/v1/orders/${rest}")
                                .removeResponseHeader(INTERNAL_DEBUG_HEADER))
                        .uri(base))
                .route("catalog", r -> r.path("/api/catalog/**")
                        .filters(f -> f
                                .rewritePath("/api/catalog/?(?<rest>.*)", "/catalog-service/v1/products/${rest}")
                                .removeResponseHeader(INTERNAL_DEBUG_HEADER)
                                .retry(c -> idempotentOnly(c, properties.getRetries())))
                        .uri(base))
                .route("reports", r -> r.path("/api/reports/**")
                        .filters(f -> f
                                .rewritePath("/api/reports/?(?<rest>.*)", "/reports-service/v1/reports/${rest}")
                                .removeResponseHeader(INTERNAL_DEBUG_HEADER))
                        .metadata(RESPONSE_TIMEOUT_ATTR, properties.reportsTimeout().toMillis())
                        .uri(base))
                .build();
    }

    /**
     * Retries GET only, only on 502 and 503, and only on I/O errors such as a
     * refused connection. Two defaults of the retry filter are turned off on
     * purpose: the 5xx series (a 500 is a bug, repeating it changes nothing)
     * and {@code TimeoutException} (a service too slow to answer once gets
     * every request three times, and the client waits three timeouts).
     */
    @SuppressWarnings("unchecked")
    static void idempotentOnly(RetryConfig config, int retries) {
        config.setRetries(retries)
                .setMethods(HttpMethod.GET)
                .setSeries()
                .setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE)
                .setExceptions(IOException.class)
                .setBackoff(Duration.ofMillis(20), Duration.ofMillis(200), 2, false);
    }
}
