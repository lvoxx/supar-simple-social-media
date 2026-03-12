package io.github.lvoxx.common_core.util;

import java.util.UUID;

import io.github.lvoxx.common_core.security.UserPrincipal;
import reactor.core.publisher.Mono;

/**
 * Utility for extracting {@link UserPrincipal} from Reactor Context.
 * The principal is stored by {@code UserPrincipalFilter} in security-starter.
 */
public final class ReactiveContextUtil {

    public static final String PRINCIPAL_CONTEXT_KEY = "userPrincipal";

    private ReactiveContextUtil() {
    }

    /** Retrieve the current {@link UserPrincipal} from Reactor Context. */
    public static Mono<UserPrincipal> getCurrentUser() {
        return Mono.deferContextual(ctx -> ctx.hasKey(PRINCIPAL_CONTEXT_KEY)
                ? Mono.just(ctx.get(PRINCIPAL_CONTEXT_KEY))
                : Mono.empty());
    }

    /** Retrieve the current user's ID from Reactor Context. */
    public static Mono<UUID> getCurrentUserId() {
        return getCurrentUser().map(UserPrincipal::userId);
    }
}
