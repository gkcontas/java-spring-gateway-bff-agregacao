package com.gkcontas.gateway.bff;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param timeouts              one per downstream call, sized to what each
 *                              part of the screen is worth waiting for
 * @param enrichmentConcurrency how many per-item catalog calls run at once
 * @param batchSize             ids per batch call; a URL has a length limit
 */
@Validated
@ConfigurationProperties("bff")
public record BffProperties(
        @NotNull(message = "bff.timeouts is required") @Valid Timeouts timeouts,
        @Min(value = 1, message = "bff.enrichment-concurrency must be at least 1") int enrichmentConcurrency,
        @Min(value = 1, message = "bff.batch-size must be at least 1")
        @Max(value = 200, message = "bff.batch-size must be at most 200") int batchSize) {

    public record Timeouts(
            @NotNull(message = "bff.timeouts.profile is required") Duration profile,
            @NotNull(message = "bff.timeouts.orders is required") Duration orders,
            @NotNull(message = "bff.timeouts.recommendations is required") Duration recommendations,
            @NotNull(message = "bff.timeouts.catalog is required") Duration catalog) {
    }
}
