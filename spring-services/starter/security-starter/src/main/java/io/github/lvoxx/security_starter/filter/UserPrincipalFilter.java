package io.github.lvoxx.security_starter.filter;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.github.lvoxx.common_core.enums.UserRole;
import io.github.lvoxx.common_core.security.UserPrincipal;
import io.github.lvoxx.common_core.util.ReactiveContextUtil;
import io.github.lvoxx.security_starter.properties.SecurityStarterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Reads gateway-injected headers and stores a {@link UserPrincipal}
 * in Reactor Context for downstream handlers.
 *
 * <pre>
 *  Incoming headers (set by K8S Ingress):
 *    X-User-Id:    01HXZ...
 *    X-User-Roles: USER,MODERATOR
 *    X-Forwarded-For: 1.2.3.4
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
public class UserPrincipalFilter implements WebFilter {

    private final SecurityStarterProperties props;
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Skip anonymous paths — no principal required
        boolean anonymous = props.getAnonymousPaths().stream()
                .anyMatch(pattern -> MATCHER.match(pattern, path));
        if (anonymous) {
            return chain.filter(exchange);
        }

        UserPrincipal principal = extractPrincipal(exchange.getRequest());
        if (principal == null) {
            log.debug("No user principal found for path={}", path);
            return chain.filter(exchange);
        }

        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(ReactiveContextUtil.PRINCIPAL_CONTEXT_KEY, principal));
    }

    private UserPrincipal extractPrincipal(ServerHttpRequest request) {
        String rawUserId = request.getHeaders().getFirst(props.getUserIdHeader());
        String rawRoles = request.getHeaders().getFirst(props.getRolesHeader());
        String ip = request.getHeaders().getFirst(props.getIpHeader());

        if (rawUserId == null || rawUserId.isBlank())
            return null;

        try {
            UUID userId = UUID.fromString(rawUserId);
            Set<UserRole> roles = rawRoles != null
                    ? Arrays.stream(rawRoles.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(UserRole::valueOf)
                            .collect(Collectors.toSet())
                    : Set.of(UserRole.USER);

            return new UserPrincipal(userId, null, roles, ip);
        } catch (Exception e) {
            log.warn("Failed to parse UserPrincipal from headers: {}", e.getMessage());
            return null;
        }
    }
}
