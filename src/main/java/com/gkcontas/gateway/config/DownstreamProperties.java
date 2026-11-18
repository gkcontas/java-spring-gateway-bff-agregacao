package com.gkcontas.gateway.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Where the internal services live. One base URL, because a single WireMock
 * plays all of them under different path prefixes.
 */
@Validated
@ConfigurationProperties("downstream")
public record DownstreamProperties(
        @NotNull(message = "downstream.base-url is required") URI baseUrl,
        @NotNull(message = "downstream.connect-timeout is required") Duration connectTimeout) {
}
