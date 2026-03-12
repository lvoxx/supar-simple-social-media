package io.github.lvoxx.metrics_starter.config;

import org.slf4j.MDC;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * WebFilter that populates MDC with traceId, spanId, userId, and requestId
 * for every inbound request.
 */
@Slf4j
public class TracingMdcFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");

        return chain.filter(exchange)
                .contextWrite(ctx -> {
                    try {
                        if (userId != null)
                            MDC.put("userId", userId);
                        if (requestId != null)
                            MDC.put("requestId", requestId);
                    } catch (Exception ignored) {
                    }
                    return ctx;
                })
                .doFinally(s -> {
                    MDC.remove("userId");
                    MDC.remove("requestId");
                });
    }
}
