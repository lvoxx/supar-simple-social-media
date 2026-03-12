package io.github.lvoxx.common_core.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inject the authenticated {@link UserPrincipal} into a handler method
 * parameter.
 *
 * <pre>{@code
 * Mono<ApiResponse<UserResponse>> getMe(@CurrentUser UserPrincipal user) { ... }
 * }</pre>
 *
 * Resolved by {@code CurrentUserArgumentResolver} registered in
 * security-starter.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
