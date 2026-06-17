package com.lvoxx.sssm.user_service.error;

/** Thrown on a state conflict (duplicate username/profile, already following). Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
