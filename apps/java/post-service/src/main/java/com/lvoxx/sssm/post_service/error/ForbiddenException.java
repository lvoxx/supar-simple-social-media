package com.lvoxx.sssm.post_service.error;

/** Thrown when the caller is authenticated but not allowed to act on the resource. Mapped to 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
