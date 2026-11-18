package com.gkcontas.gateway.security;

import com.gkcontas.gateway.web.ErrorWriter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.gkcontas.gateway.web.RequestContext.*;

/**
 * Turns a bearer token into the identity headers the internal services trust.
 *
 * <ul>
 *   <li>{@code X-User-Id} and {@code X-User-Roles} sent by the client are
 *       removed on every request, public routes included. Otherwise anyone can
 *       be anyone downstream.</li>
 *   <li>Deny by default: only the paths listed as public skip the token.</li>
 *   <li>The {@code Authorization} header does not go further in. The services
 *       trust the gateway's headers, which assumes only the gateway can reach
 *       them.</li>
 * </ul>
 *
 * A {@code WebFilter} so that it guards the routed requests and the BFF
 * controller alike.
 */
@Component
public class AuthenticationWebFilter implements WebFilter, Ordered {

    private static final String BEARER = "bearer ";

    private final JwtVerifier verifier;
    private final ErrorWriter errors;
    private final List<PathPattern> publicPaths;
    private final List<CompiledRule> roleRules;

    public AuthenticationWebFilter(JwtVerifier verifier, ErrorWriter errors, AuthProperties properties) {
        this.verifier = verifier;
        this.errors = errors;
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.publicPaths = properties.publicPaths().stream().map(parser::parse).toList();
        this.roleRules = properties.roleRules().stream()
                .map(r -> new CompiledRule(parser.parse(r.path()), r.role()))
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(USER_ID_HEADER);
                    h.remove(USER_ROLES_HEADER);
                })
                .build();
        PathContainer path = stripped.getPath().pathWithinApplication();

        if (publicPaths.stream().anyMatch(p -> p.matches(path))) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        String authorization = stripped.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.length() <= BEARER.length()
                || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return unauthorized(exchange, "Missing bearer token");
        }

        AuthenticatedUser user;
        try {
            user = verifier.verify(authorization.substring(BEARER.length()).trim());
        } catch (InvalidTokenException e) {
            return unauthorized(exchange, e.getMessage());
        }

        for (CompiledRule rule : roleRules) {
            if (rule.pattern().matches(path) && !user.roles().contains(rule.role())) {
                return errors.write(exchange, HttpStatus.FORBIDDEN, "Role '" + rule.role() + "' is required");
            }
        }

        AuthenticatedUser authenticated = user;
        ServerHttpRequest request = stripped.mutate()
                .headers(h -> {
                    h.remove(HttpHeaders.AUTHORIZATION);
                    h.set(USER_ID_HEADER, authenticated.id());
                    h.set(USER_ROLES_HEADER, authenticated.rolesHeader());
                })
                .build();
        exchange.getAttributes().put(USER_ATTR, authenticated);
        return chain.filter(exchange.mutate().request(request).build())
                .contextWrite(ctx -> ctx.put(USER_KEY, authenticated));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        return errors.write(exchange, HttpStatus.UNAUTHORIZED, message);
    }

    @Override
    public int getOrder() {
        return ORDER_AUTHENTICATION;
    }

    private record CompiledRule(PathPattern pattern, String role) {
    }
}
