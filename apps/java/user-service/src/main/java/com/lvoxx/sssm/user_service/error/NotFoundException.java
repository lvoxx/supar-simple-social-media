package com.lvoxx.sssm.user_service.error;

/** Thrown when a requested resource (profile, follow edge) does not exist. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
