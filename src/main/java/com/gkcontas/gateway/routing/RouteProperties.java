package com.gkcontas.gateway.routing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The default response timeout is the global one of the gateway's HTTP client
 * ({@code spring.cloud.gateway.server.webflux.httpclient.response-timeout});
 * the reports route overrides it.
 */
@Validated
@ConfigurationProperties("gateway.routes")
public record RouteProperties(
        @NotNull(message = "gateway.routes.reports-timeout is required") Duration reportsTimeout,
        @Min(value = 0, message = "gateway.routes.get-retries must be zero or more")
        @Max(value = 5, message = "gateway.routes.get-retries must be at most 5")
        int getRetries) {
}
