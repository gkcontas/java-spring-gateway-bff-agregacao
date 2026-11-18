package com.gkcontas.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextIT extends IntegrationTestBase {

    @Test
    void contextStartsAndHealthIsUp() {
        String body = WebClient.create("http://localhost:" + port)
                .get().uri("/actuator/health")
                .retrieve().bodyToMono(String.class).block();
        assertThat(body).contains("UP");
    }
}
