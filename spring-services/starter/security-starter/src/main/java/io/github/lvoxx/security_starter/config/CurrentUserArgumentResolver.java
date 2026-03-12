package io.github.lvoxx.security_starter.config;

import java.nio.file.attribute.UserPrincipal;

import org.springframework.core.MethodParameter;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;

import io.github.lvoxx.common_core.security.CurrentUser;
import io.github.lvoxx.common_core.util.ReactiveContextUtil;
import reactor.core.publisher.Mono;

/**
 * Resolves {@code @CurrentUser UserPrincipal} method parameters
 * by reading the principal from Reactor Context.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(CurrentUser.class)
                                && UserPrincipal.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Mono<Object> resolveArgument(
                        MethodParameter parameter,
                        BindingContext bindingContext,
                        ServerWebExchange exchange) {
                return ReactiveContextUtil.getCurrentUser().cast(Object.class);
        }
}