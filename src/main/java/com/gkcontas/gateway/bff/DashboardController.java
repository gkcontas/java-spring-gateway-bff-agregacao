package com.gkcontas.gateway.bff;

import com.gkcontas.gateway.security.AuthenticatedUser;
import com.gkcontas.gateway.web.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.gkcontas.gateway.web.RequestContext.USER_ATTR;

/**
 * The BFF endpoint. The user comes from the authentication filter, never from a
 * parameter: {@code /bff/dashboard} has no user id in it to tamper with.
 */
@RestController
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/bff/dashboard")
    public Mono<Dashboard> dashboard(@RequestParam(defaultValue = "parallel") String strategy,
                                     @RequestParam(defaultValue = "batch") String enrichment,
                                     ServerWebExchange exchange) {
        AuthenticatedUser user = exchange.getRequiredAttribute(USER_ATTR);
        return service.dashboard(user,
                parse(Strategy.class, "strategy", strategy),
                parse(Enrichment.class, "enrichment", enrichment));
    }

    static <E extends Enum<E>> E parse(Class<E> type, String name, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            String accepted = Arrays.stream(type.getEnumConstants())
                    .map(c -> c.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                    .collect(Collectors.joining(", "));
            throw new BadRequestException(name + " must be one of: " + accepted);
        }
    }

    @ExceptionHandler(DownstreamException.class)
    ResponseEntity<ApiError> downstream(DownstreamException e) {
        return error(e.status(), e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), status.getReasonPhrase(), message));
    }
}
