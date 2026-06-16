package com.lvoxx.sssm.media.error;

/** Thrown when the caller may not act on a resource they do not own. Mapped to HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
