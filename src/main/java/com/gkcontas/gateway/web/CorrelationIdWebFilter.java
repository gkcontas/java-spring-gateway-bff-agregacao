package com.gkcontas.gateway.web;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

import static com.gkcontas.gateway.web.RequestContext.*;

/**
 * Gives every request one id and carries it everywhere: the request that goes
 * downstream, the response, the exchange attributes (for the access log) and
 * the Reactor context (for the BFF's own outbound calls).
 *
 * <p>A {@code WebFilter}, not a gateway {@code GlobalFilter}: global filters
 * only run on routed requests, and the BFF is a local controller.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    /** An inbound id is kept only if it cannot break a log line or a header. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    static String resolve(String inbound) {
        if (inbound != null && SAFE_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolve(exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER));
        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(REQUEST_ID_HEADER, requestId))
                .build();
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build())
                .contextWrite(ctx -> ctx.put(REQUEST_ID_KEY, requestId));
    }

    @Override
    public int getOrder() {
        return ORDER_CORRELATION;
    }
}
