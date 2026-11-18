package com.gkcontas.gateway.web;

import com.gkcontas.gateway.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.gkcontas.gateway.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(OutputCaptureExtension.class)
class AccessLogIT extends IntegrationTestBase {

    @Test
    void logsRouteStatusAndUserButNotTheTokenNorTheQueryString(CapturedOutput output) {
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42")).willReturn(okJson("{}")));
        String authorization = bearer("42");

        client().get().uri("/api/users/42?email=ana@example.com")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header("X-Request-Id", "access-log-0001")
                .exchange().expectStatus().isOk();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut())
                .contains("requestId=access-log-0001 method=GET path=/api/users/42 status=200")
                .contains("route=users user=42"));
        assertThat(output.getOut())
                .doesNotContain(authorization.substring(7))
                .doesNotContain("ana@example.com");
    }

    @Test
    void logsTheStatusTheErrorHandlerWrote(CapturedOutput output) {
        DOWNSTREAM.register(get(urlPathEqualTo("/users-service/v1/users/42"))
                .willReturn(okJson("{}").withFixedDelay(2000)));

        client().get().uri("/api/users/42")
                .header(HttpHeaders.AUTHORIZATION, bearer("42"))
                .header("X-Request-Id", "access-log-0002")
                .exchange().expectStatus().isEqualTo(504);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut())
                .contains("requestId=access-log-0002 method=GET path=/api/users/42 status=504"));
    }
}
