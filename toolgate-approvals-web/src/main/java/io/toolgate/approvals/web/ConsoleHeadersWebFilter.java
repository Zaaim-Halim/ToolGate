package io.toolgate.approvals.web;

import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Adds the console's headers to every response under {@code /toolgate/}, on Spring WebFlux. */
final class ConsoleHeadersWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().pathWithinApplication().value().startsWith(ConsoleHeaders.PATH_PREFIX)) {
            exchange.getResponse().getHeaders().setAll(ConsoleHeaders.HEADERS);
        }
        return chain.filter(exchange);
    }
}
