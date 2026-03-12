package io.github.lvoxx.common_core.validation;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.lvoxx.common_core.exception.ValidationException;
import io.github.lvoxx.common_core.message.MessageKeys;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Reactive bean validation wrapper.
 * Safe to use in WebFlux / R2DBC pipelines (non-blocking).
 */
@Slf4j
@Component
public class ReactiveValidator {
    private final Validator validator;

    public ReactiveValidator(Validator validator) {
        this.validator = validator;
    }

    /**
     * Validate {@code object} against its constraint annotations.
     *
     * @return {@code Mono.just(object)} if valid,
     *         {@code Mono.error(ValidationException)} otherwise.
     */
    public <T> Mono<T> validate(T object) {
        Set<ConstraintViolation<T>> violations = validator.validate(object);

        if (violations.isEmpty()) {
            return Mono.just(object);
        }

        String details = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));

        return Mono.error(
                new ValidationException(MessageKeys.INTERNAL_ERROR, details));
    }
}
