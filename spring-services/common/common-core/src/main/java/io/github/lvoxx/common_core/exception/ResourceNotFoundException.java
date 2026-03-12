package io.github.lvoxx.common_core.exception;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException() {
        super();
    }

    public ResourceNotFoundException(String errorCode, Object... args) {
        super(errorCode, args);
    }
}
