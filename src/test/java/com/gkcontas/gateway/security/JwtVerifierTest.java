package com.gkcontas.gateway.security;

import com.gkcontas.gateway.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static com.gkcontas.gateway.TestTokens.ISSUER;
import static com.gkcontas.gateway.TestTokens.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtVerifierTest {

    private final Instant now = Instant.now();
    private final JwtVerifier verifier = new JwtVerifier(
            new AuthProperties(SECRET, ISSUER, Duration.ofSeconds(30), List.of(), List.of()),
            Clock.fixed(now, ZoneOffset.UTC));

    private String token(String secret, String issuer, String subject, List<String> roles, Instant exp) {
        return TestTokens.token(secret, issuer, subject, roles, exp);
    }

    @Test
    void acceptsAValidTokenAndReadsSubjectAndRoles() {
        AuthenticatedUser user = verifier.verify(token(SECRET, ISSUER, "42", List.of("analyst", "customer"), now.plusSeconds(60)));
        assertThat(user.id()).isEqualTo("42");
        assertThat(user.roles()).containsExactlyInAnyOrder("analyst", "customer");
        assertThat(user.rolesHeader()).isEqualTo("analyst,customer");
    }

    @Test
    void tokenWithoutRolesHasNone() {
        assertThat(verifier.verify(token(SECRET, ISSUER, "42", null, now.plusSeconds(60))).roles()).isEmpty();
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        String forged = token("another-secret-with-at-least-thirty-two-b", ISSUER, "42", List.of(), now.plusSeconds(60));
        assertThatThrownBy(() -> verifier.verify(forged)).hasMessage("Token signature is invalid");
    }

    @Test
    void rejectsATamperedPayload() {
        String[] parts = token(SECRET, ISSUER, "42", List.of(), now.plusSeconds(60)).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("\"42\"", "\"1\"");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + "." + parts[2];
        assertThatThrownBy(() -> verifier.verify(tampered)).hasMessage("Token signature is invalid");
    }

    @Test
    void rejectsAnUnsignedTokenWithAlgNone() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"42\",\"iss\":\"" + ISSUER + "\",\"exp\":" + now.plusSeconds(60).getEpochSecond() + "}").getBytes());
        assertThatThrownBy(() -> verifier.verify(header + "." + payload + "."))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAnExpiredTokenBeyondTheClockSkew() {
        assertThatThrownBy(() -> verifier.verify(token(SECRET, ISSUER, "42", List.of(), now.minusSeconds(31))))
                .hasMessage("Token has expired");
    }

    @Test
    void toleratesExpirationWithinTheClockSkew() {
        assertThat(verifier.verify(token(SECRET, ISSUER, "42", List.of(), now.minusSeconds(20))).id()).isEqualTo("42");
    }

    @Test
    void rejectsATokenWithoutExpiration() {
        assertThatThrownBy(() -> verifier.verify(token(SECRET, ISSUER, "42", List.of(), null)))
                .hasMessage("Token has no expiration");
    }

    @Test
    void rejectsAnotherIssuer() {
        assertThatThrownBy(() -> verifier.verify(token(SECRET, "https://evil.example", "42", List.of(), now.plusSeconds(60))))
                .hasMessage("Token issuer is not accepted");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt")).hasMessage("Token is malformed");
    }
}
