package com.gkcontas.gateway.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * @param hmacSecret  HS256 needs a key of at least 256 bits; a shorter one is
 *                    refused at startup instead of at the first request
 * @param publicPaths everything else is protected: a new route is closed until
 *                    someone declares it public
 * @param roleRules   a role required by path prefix, checked after the token
 */
@Validated
@ConfigurationProperties("gateway.auth")
public record AuthProperties(
        @NotBlank(message = "gateway.auth.hmac-secret is required")
        @Size(min = 32, message = "gateway.auth.hmac-secret must have at least 32 characters")
        String hmacSecret,
        @NotBlank(message = "gateway.auth.issuer is required") String issuer,
        @NotNull(message = "gateway.auth.clock-skew is required") Duration clockSkew,
        List<String> publicPaths,
        List<@Valid RoleRule> roleRules) {

    public AuthProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        roleRules = roleRules == null ? List.of() : List.copyOf(roleRules);
    }

    public record RoleRule(
            @NotBlank(message = "role rule path is required") String path,
            @NotBlank(message = "role rule role is required") String role) {
    }
}
