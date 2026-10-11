package io.toolgate.approvals.web;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The REST API on Spring WebFlux. ToolGate's stores, such as JDBC, block, so every call runs on
 * Reactor's bounded elastic scheduler, never on an event loop thread.
 */
@RestController
@RequestMapping("/toolgate/api/approvals")
final class ApprovalsWebFluxController {

    private final ApprovalsApi api;

    ApprovalsWebFluxController(ApprovalsApi api) {
        this.api = api;
    }

    @GetMapping
    Mono<List<ApiModel.Item>> inbox(Principal user) {
        return blocking(() -> api.inbox(user));
    }

    @GetMapping("/item")
    Mono<ApiModel.Item> item(Principal user, @RequestParam(required = false) String id) {
        return blocking(() -> api.item(user, id));
    }

    @GetMapping("/me")
    Mono<ApiModel.Me> me(Principal user) {
        return blocking(() -> api.me(user));
    }

    @PostMapping("/decisions")
    Mono<ApiModel.DecisionResponse> decide(Principal user,
                                           @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                           @RequestHeader(value = RequestGuard.HEADER, required = false) String marker,
                                           @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
                                           @RequestHeader(value = HttpHeaders.HOST, required = false) String hostHeader,
                                           ServerHttpRequest http,
                                           @RequestBody(required = false) ApiModel.DecisionRequest request) {
        // The Host header, as the browser addressed it, so a proxy that ends TLS does not change the port.
        int port = http.getURI().getPort() != -1 ? http.getURI().getPort()
                : "https".equalsIgnoreCase(http.getURI().getScheme()) ? 443 : 80;
        String host = hostHeader != null ? hostHeader : http.getURI().getHost() + ":" + port;
        return blocking(() -> {
            RequestGuard.check(contentType, marker, origin, host);
            return api.decide(user, request);
        });
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiModel.Problem> refused(ApiException refused) {
        return ResponseEntity.status(refused.status()).body(new ApiModel.Problem(refused.getMessage()));
    }

    private static <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
