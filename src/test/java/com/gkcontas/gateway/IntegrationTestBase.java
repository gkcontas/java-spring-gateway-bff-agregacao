package com.gkcontas.gateway;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One WireMock container for the whole suite, started from a static
 * initialiser. It plays every downstream service the gateway talks to. Not
 * {@code @Testcontainers}: Spring caches application contexts across test
 * classes, and a container tied to a class is stopped while a cached context
 * still points at it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @SuppressWarnings("resource")
    static final GenericContainer<?> WIREMOCK = new GenericContainer<>("wiremock/wiremock:3.9.2")
            .withExposedPorts(8080)
            .withCommand("--disable-banner", "--async-response-enabled=true")
            .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

    static {
        WIREMOCK.start();
    }

    protected static final WireMock DOWNSTREAM =
            new WireMock(WIREMOCK.getHost(), WIREMOCK.getMappedPort(8080));

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("downstream.base-url",
                () -> "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080));
    }

    @BeforeEach
    void resetDownstream() {
        DOWNSTREAM.resetMappings();
        DOWNSTREAM.resetRequests();
        DOWNSTREAM.resetScenarios();
    }
}
