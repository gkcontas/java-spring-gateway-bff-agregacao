package com.gkcontas.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Mints HS256 tokens with the secret of the test profile. */
public final class TestTokens {

    public static final String SECRET = "test-secret-with-at-least-thirty-two-bytes";
    public static final String ISSUER = "https://auth.example.local";

    private TestTokens() {
    }

    public static String bearer(String subject, String... roles) {
        return "Bearer " + token(SECRET, ISSUER, subject, List.of(roles), Instant.now().plusSeconds(300));
    }

    public static String token(String secret, String issuer, String subject, List<String> roles, Instant expiresAt) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .issueTime(new Date())
                .expirationTime(expiresAt == null ? null : Date.from(expiresAt));
        if (roles != null) {
            claims.claim("roles", roles);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        try {
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }
}
