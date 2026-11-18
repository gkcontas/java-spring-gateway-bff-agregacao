package com.gkcontas.gateway.bff;

import com.gkcontas.gateway.config.DownstreamProperties;
import com.gkcontas.gateway.security.AuthenticatedUser;
import io.netty.channel.ChannelOption;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static com.gkcontas.gateway.web.RequestContext.*;

/**
 * The BFF's calls to the internal services. Each one carries the request id and
 * the user of the inbound request, read from the Reactor context: the call may
 * run on any event loop thread, so a ThreadLocal would hand it someone else's.
 *
 * <p>No response timeout on the HTTP client: each caller applies its own with
 * {@code timeout()}, which also cancels the request.
 */
@Component
public class DownstreamClient {

    private static final ParameterizedTypeReference<List<Downstream.Order>> ORDERS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Downstream.Recommendation>> RECOMMENDATIONS =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<Downstream.Product>> PRODUCTS =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient web;

    public DownstreamClient(WebClient.Builder builder, DownstreamProperties properties) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis());
        this.web = builder.clone()
                .baseUrl(properties.baseUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(http))
                .filter(propagateContext())
                .build();
    }

    static ExchangeFilterFunction propagateContext() {
        return (request, next) -> Mono.deferContextual(ctx -> {
            ClientRequest.Builder outbound = ClientRequest.from(request);
            ctx.<String>getOrEmpty(REQUEST_ID_KEY).ifPresent(id -> outbound.header(REQUEST_ID_HEADER, id));
            ctx.<AuthenticatedUser>getOrEmpty(USER_KEY).ifPresent(user -> outbound
                    .header(USER_ID_HEADER, user.id())
                    .header(USER_ROLES_HEADER, user.rolesHeader()));
            return next.exchange(outbound.build());
        });
    }

    public Mono<Downstream.UserProfile> profile(String userId) {
        return web.get().uri("/users-service/v1/users/{id}", userId)
                .retrieve().bodyToMono(Downstream.UserProfile.class);
    }

    public Mono<List<Downstream.Order>> orders(String userId) {
        return web.get().uri(b -> b.path("/orders-service/v1/orders").queryParam("userId", userId).build())
                .retrieve().bodyToMono(ORDERS);
    }

    public Mono<List<Downstream.Recommendation>> recommendations(String userId) {
        return web.get().uri(b -> b.path("/recommendations-service/v1/recommendations")
                        .queryParam("userId", userId).build())
                .retrieve().bodyToMono(RECOMMENDATIONS);
    }

    public Mono<Downstream.Product> product(long id) {
        return web.get().uri("/catalog-service/v1/products/{id}", id)
                .retrieve().bodyToMono(Downstream.Product.class);
    }

    public Mono<List<Downstream.Product>> products(Collection<Long> ids) {
        String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return web.get().uri(b -> b.path("/catalog-service/v1/products").queryParam("ids", joined).build())
                .retrieve().bodyToMono(PRODUCTS);
    }
}
