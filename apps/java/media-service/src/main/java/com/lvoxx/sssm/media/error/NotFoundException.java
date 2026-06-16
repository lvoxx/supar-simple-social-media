package com.lvoxx.sssm.media.error;

/** Thrown when a requested resource (media) does not exist. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
