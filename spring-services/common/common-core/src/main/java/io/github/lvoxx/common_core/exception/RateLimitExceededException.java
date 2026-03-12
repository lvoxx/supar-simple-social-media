package io.github.lvoxx.common_core.exception;

public class RateLimitExceededException extends BusinessException {

    public RateLimitExceededException() {
        super();
    }

    public RateLimitExceededException(String errorCode, Object... args) {
        super(errorCode, args);
    }
}
