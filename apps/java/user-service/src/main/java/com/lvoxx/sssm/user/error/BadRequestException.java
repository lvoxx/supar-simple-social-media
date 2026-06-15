package com.lvoxx.sssm.user.error;

/** Thrown on a semantically invalid request (self-follow, malformed cursor). Mapped to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
