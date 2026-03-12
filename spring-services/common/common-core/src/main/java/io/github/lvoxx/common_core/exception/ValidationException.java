package io.github.lvoxx.common_core.exception;

public class ValidationException extends BusinessException {

    public ValidationException() {
        super();
    }

    public ValidationException(String errorCode, Object... args) {
        super(errorCode, args);
    }
}
