package io.github.lvoxx.common_core.model;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        List<String> details,
        Instant timestamp) {
    public ErrorResponse(String code, String message, List<String> details) {
        this(code, message, details, Instant.now());
    }

    public ErrorResponse(String code, String message) {
        this(code, message, List.of(), Instant.now());
    }
}
