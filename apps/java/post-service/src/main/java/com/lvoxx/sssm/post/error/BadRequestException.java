package com.lvoxx.sssm.post.error;

/** Thrown when the caller's request is malformed or violates a domain rule. Mapped to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
