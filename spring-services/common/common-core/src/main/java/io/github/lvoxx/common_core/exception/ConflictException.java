package io.github.lvoxx.common_core.exception;

public class ConflictException extends BusinessException {

    public ConflictException() {
        super();
    }

    public ConflictException(String errorCode, Object... args) {
        super(errorCode, args);
    }
}
