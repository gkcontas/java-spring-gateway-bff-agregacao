package com.gkcontas.gateway.bff;

import com.gkcontas.gateway.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Builds the dashboard from four services.
 *
 * <ul>
 *   <li>The profile is required: without it there is no screen, and the
 *       response is 502 or 504.</li>
 *   <li>Orders, recommendations and product names are optional: each has its
 *       own timeout, and when it fails the part comes back empty and is listed
 *       in {@code degraded}. A slow recommendation engine costs the screen at
 *       most its timeout, not its latency.</li>
 * </ul>
 *
 * The Monos below are cold: declaring them fires nothing. Whether they run in
 * parallel or in sequence is decided only by how they are composed.
 */
@Service
public class DashboardService {

    static final String ORDERS = "orders";
    static final String RECOMMENDATIONS = "recommendations";
    static final String CATALOG = "catalog";

    private final DownstreamClient client;
    private final BffProperties properties;

    public DashboardService(DownstreamClient client, BffProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public Mono<Dashboard> dashboard(AuthenticatedUser user, Strategy strategy, Enrichment enrichment) {
        BffProperties.Timeouts timeouts = properties.timeouts();

        Mono<Downstream.UserProfile> profile = client.profile(user.id())
                .timeout(timeouts.profile())
                .onErrorMap(DashboardService::requiredFailure);

        Mono<Part<List<Dashboard.OrderView>>> orders = client.orders(user.id())
                .timeout(timeouts.orders())
                .flatMap(list -> enrich(list, enrichment))
                .onErrorResume(e -> Mono.just(Part.degraded(List.of(), ORDERS)));

        Mono<Part<List<Downstream.Recommendation>>> recommendations = client.recommendations(user.id())
                .timeout(timeouts.recommendations())
                .map(Part::ok)
                .onErrorResume(e -> Mono.just(Part.degraded(List.of(), RECOMMENDATIONS)));

        return switch (strategy) {
            case PARALLEL -> Mono.zip(profile, orders, recommendations)
                    .map(t -> assemble(t.getT1(), t.getT2(), t.getT3()));
            case SEQUENTIAL -> profile.flatMap(p -> orders.flatMap(o -> recommendations.map(r -> assemble(p, o, r))));
        };
    }

    private Mono<Part<List<Dashboard.OrderView>>> enrich(List<Downstream.Order> orders, Enrichment enrichment) {
        List<Long> lineProductIds = orders.stream()
                .flatMap(o -> o.items().stream())
                .map(Downstream.OrderItem::productId)
                .toList();
        if (lineProductIds.isEmpty()) {
            return Mono.just(Part.ok(views(orders, Map.of())));
        }
        Flux<Downstream.Product> products = switch (enrichment) {
            case PER_ITEM -> Flux.fromIterable(lineProductIds)
                    .flatMap(client::product, properties.enrichmentConcurrency());
            case BATCH -> Flux.fromIterable(chunks(new ArrayList<>(new LinkedHashSet<>(lineProductIds))))
                    .flatMap(client::products, properties.enrichmentConcurrency())
                    .flatMapIterable(list -> list);
        };
        return products
                .collect(HashMap<Long, String>::new, (map, p) -> map.put(p.id(), p.name()))
                .timeout(properties.timeouts().catalog())
                .map(names -> Part.ok(views(orders, names)))
                .onErrorResume(e -> Mono.just(Part.degraded(views(orders, Map.of()), CATALOG)));
    }

    private List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += properties.batchSize()) {
            chunks.add(ids.subList(i, Math.min(ids.size(), i + properties.batchSize())));
        }
        return chunks;
    }

    private static List<Dashboard.OrderView> views(List<Downstream.Order> orders, Map<Long, String> names) {
        return orders.stream()
                .map(o -> new Dashboard.OrderView(o.id(), o.status(), o.total(), o.items().stream()
                        .map(i -> new Dashboard.OrderLine(i.productId(), i.quantity(), names.get(i.productId())))
                        .toList()))
                .toList();
    }

    private static Dashboard assemble(Downstream.UserProfile profile,
                                      Part<List<Dashboard.OrderView>> orders,
                                      Part<List<Downstream.Recommendation>> recommendations) {
        List<String> degraded = new ArrayList<>(orders.degraded());
        degraded.addAll(recommendations.degraded());
        return new Dashboard(profile, orders.value(), recommendations.value(), List.copyOf(degraded));
    }

    static Throwable requiredFailure(Throwable error) {
        if (error instanceof TimeoutException) {
            return new DownstreamException(HttpStatus.GATEWAY_TIMEOUT, "User profile service timed out", error);
        }
        if (error instanceof WebClientResponseException.NotFound) {
            return new DownstreamException(HttpStatus.NOT_FOUND, "User profile not found", error);
        }
        return new DownstreamException(HttpStatus.BAD_GATEWAY, "User profile service is unavailable", error);
    }

    record Part<T>(T value, List<String> degraded) {

        static <T> Part<T> ok(T value) {
            return new Part<>(value, List.of());
        }

        static <T> Part<T> degraded(T value, String part) {
            return new Part<>(value, List.of(part));
        }
    }
}
