package com.gkcontas.gateway.web;

import com.gkcontas.gateway.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.gkcontas.gateway.web.RequestContext.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * One line per request: id, method, path, status, duration, route and user.
 * Never the Authorization header and never the query string, which is where
 * tokens and personal data end up.
 *
 * <p>The line is written when the response is committed, not when the filter
 * chain completes: an error raised downstream (a gateway timeout, say) leaves
 * the chain before the error handler sets the status, so logging on completion
 * would record the wrong one.
 */
@Component
public class AccessLogWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.nanoTime();
        AtomicBoolean logged = new AtomicBoolean();
        exchange.getResponse().beforeCommit(() -> {
            if (logged.compareAndSet(false, true)) {
                write(exchange, start, statusOf(exchange));
            }
            return Mono.empty();
        });
        return chain.filter(exchange).doFinally(signal -> {
            if (signal == SignalType.CANCEL && logged.compareAndSet(false, true)) {
                write(exchange, start, "cancelled");
            }
        });
    }

    private static String statusOf(ServerWebExchange exchange) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        return status == null ? "200" : String.valueOf(status.value());
    }

    private static void write(ServerWebExchange exchange, long start, String status) {
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        String path = exchange.getRequest().getPath().value();
        String routeId = route != null ? route.getId() : path.startsWith("/bff/") ? "bff" : "-";
        AuthenticatedUser user = exchange.getAttribute(USER_ATTR);
        log.info("requestId={} method={} path={} status={} durationMs={} route={} user={}",
                exchange.getAttribute(REQUEST_ID_ATTR), exchange.getRequest().getMethod(), path,
                status, millis, routeId, user == null ? "-" : user.id());
    }

    @Override
    public int getOrder() {
        return ORDER_ACCESS_LOG;
    }
}
