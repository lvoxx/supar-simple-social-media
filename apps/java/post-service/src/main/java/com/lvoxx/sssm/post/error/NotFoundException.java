package com.lvoxx.sssm.post.error;

/** Thrown when a requested resource (post) does not exist. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
