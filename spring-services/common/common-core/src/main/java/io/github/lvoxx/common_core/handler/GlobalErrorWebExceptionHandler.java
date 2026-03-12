package io.github.lvoxx.common_core.handler;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.lvoxx.common_core.exception.BusinessException;
import io.github.lvoxx.common_core.exception.ConflictException;
import io.github.lvoxx.common_core.exception.ExternalServiceException;
import io.github.lvoxx.common_core.exception.ForbiddenException;
import io.github.lvoxx.common_core.exception.RateLimitExceededException;
import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_core.exception.ValidationException;
import io.github.lvoxx.common_core.message.MessageKeys;
import io.github.lvoxx.common_core.model.ApiResponse;
import io.github.lvoxx.common_core.model.ErrorResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalErrorWebExceptionHandler {
    private final MessageSource messageSource;

    public GlobalErrorWebExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<?> handleNotFound(ResourceNotFoundException ex) {
        log.error("Resource not found", ex);
        return ApiResponse.error(buildError(ex));
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleConflict(ConflictException ex) {
        log.error("Conflict", ex);
        return ApiResponse.error(buildError(ex));
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<?> handleForbidden(ForbiddenException ex) {
        log.error("Forbidden", ex);
        return ApiResponse.error(buildError(ex));
    }

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ApiResponse<?> handleValidation(ValidationException ex) {
        log.error("Validation failed", ex);
        return ApiResponse.error(buildError(ex));
    }

    @ExceptionHandler(ExternalServiceException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<?> handleExternal(ExternalServiceException ex) {
        log.error("External service error", ex);
        return ApiResponse.error(buildError(ex));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiResponse<?> handleRateLimit(RateLimitExceededException ex) {
        log.error("Rate limit exceeded", ex);
        return ApiResponse.error(buildError(ex));
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleBusiness(BusinessException ex) {
        log.error("Business exception", ex);
        return ApiResponse.error(buildError(ex));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleGeneric(Exception ex) {
        log.error("Unhandled exception occurred", ex);
        ErrorResponse error = new ErrorResponse(
                MessageKeys.INTERNAL_ERROR,
                getMessage(MessageKeys.INTERNAL_ERROR),
                List.of(ex.getMessage()));
        return ApiResponse.error(error);
    }

    private ErrorResponse buildError(BusinessException e) {
        return new ErrorResponse(
                e.getErrorCode(),
                getMessage(e.getErrorCode(), e.getArgs()),
                List.of());
    }

    // old mapping logic replaced by individual handlers above

    private String getMessage(String code, Object... args) {
        try {
            return messageSource.getMessage(code, args, Locale.getDefault());
        } catch (Exception e) {
            return code;
        }
    }
}
