package com.lvoxx.sssm.common.core;

import java.time.Instant;
import java.util.List;

/**
 * Standard error response body shared by every SSSM Java service so API clients see one
 * consistent error shape regardless of which service responded.
 *
 * @param timestamp when the error occurred (UTC instant)
 * @param status    HTTP status code
 * @param error     short reason phrase (e.g. "Bad Request")
 * @param message   human-readable detail
 * @param path      request path that produced the error
 * @param details   optional field-level validation messages
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> details) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, List.of());
    }
}
