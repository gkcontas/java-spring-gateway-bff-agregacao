package com.gkcontas.gateway.web;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Locale;

import static com.gkcontas.gateway.web.RequestContext.ORDER_PATH_GUARD;

/**
 * Authentication decides on one reading of the path, routing on another, and
 * the downstream service on a third. Dot segments, encoded dots and slashes,
 * backslashes and matrix parameters are where those readings diverge - a path
 * that looks public to the gateway can resolve to a protected one further in.
 * They are refused before any decision is made.
 */
@Component
public class PathGuardWebFilter implements WebFilter, Ordered {

    static final String MESSAGE = "Request path contains a disallowed sequence";

    private final ErrorWriter errors;

    public PathGuardWebFilter(ErrorWriter errors) {
        this.errors = errors;
    }

    static boolean isDisallowed(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")
                || lower.contains("%3b") || lower.contains("%00")
                || lower.contains("\\") || lower.contains(";")) {
            return true;
        }
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isDisallowed(exchange.getRequest().getURI().getRawPath())) {
            return errors.write(exchange, HttpStatus.BAD_REQUEST, MESSAGE);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return ORDER_PATH_GUARD;
    }
}
