package com.gkcontas.gateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

/**
 * Validates an HS256 token. The algorithm is pinned instead of read from the
 * header: a verifier that trusts the header's {@code alg} accepts whatever the
 * client chose to sign with, including nothing.
 */
@Component
public class JwtVerifier {

    private final MACVerifier verifier;
    private final String issuer;
    private final Duration clockSkew;
    private final Clock clock;

    public JwtVerifier(AuthProperties properties, Clock clock) {
        try {
            this.verifier = new MACVerifier(properties.hmacSecret().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException("gateway.auth.hmac-secret is not a valid HS256 key", e);
        }
        this.issuer = properties.issuer();
        this.clockSkew = properties.clockSkew();
        this.clock = clock;
    }

    public AuthenticatedUser verify(String token) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new InvalidTokenException("Token is malformed");
        }
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new InvalidTokenException("Token algorithm is not accepted");
        }
        try {
            if (!jwt.verify(verifier)) {
                throw new InvalidTokenException("Token signature is invalid");
            }
            claims = jwt.getJWTClaimsSet();
        } catch (JOSEException | ParseException e) {
            throw new InvalidTokenException("Token signature is invalid");
        }

        Instant now = clock.instant();
        Date expiration = claims.getExpirationTime();
        if (expiration == null) {
            throw new InvalidTokenException("Token has no expiration");
        }
        if (now.isAfter(expiration.toInstant().plus(clockSkew))) {
            throw new InvalidTokenException("Token has expired");
        }
        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null && now.plus(clockSkew).isBefore(notBefore.toInstant())) {
            throw new InvalidTokenException("Token is not valid yet");
        }
        if (!issuer.equals(claims.getIssuer())) {
            throw new InvalidTokenException("Token issuer is not accepted");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("Token has no subject");
        }
        List<String> roles;
        try {
            roles = claims.getStringListClaim("roles");
        } catch (ParseException e) {
            throw new InvalidTokenException("Token roles claim is malformed");
        }
        return new AuthenticatedUser(subject, roles == null ? new HashSet<>() : new HashSet<>(roles));
    }
}
