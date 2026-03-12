package io.github.lvoxx.common_core.exception;

import java.util.Arrays;

public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final Object[] args;

    public BusinessException() {
        super();
        this.errorCode = "unknown_error";
        this.args = new Object[0];
    }

    public BusinessException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
        this.args = new Object[0];
    }

    public BusinessException(String errorCode, Object... args) {
        super(errorCode);
        this.errorCode = errorCode;
        this.args = args != null ? args.clone() : new Object[0];
    }

    public BusinessException(String errorCode, Throwable cause, Object... args) {
        super(errorCode, cause);
        this.errorCode = errorCode;
        this.args = args != null ? args.clone() : new Object[0];
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object[] getArgs() {
        return args.clone();
    }

    @Override
    public String toString() {
        return "BusinessException{" +
                "errorCode='" + errorCode + '\'' +
                ", args=" + Arrays.toString(args) +
                '}';
    }
}