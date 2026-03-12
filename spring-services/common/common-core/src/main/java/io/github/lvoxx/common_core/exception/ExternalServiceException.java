package io.github.lvoxx.common_core.exception;

public class ExternalServiceException extends BusinessException {
    
    public ExternalServiceException() {
        super();
    }
    
    public ExternalServiceException(String errorCode, Object... args) {
        super(errorCode, args);
    }
}
