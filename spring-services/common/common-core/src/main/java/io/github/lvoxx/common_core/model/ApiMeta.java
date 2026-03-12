package io.github.lvoxx.common_core.model;

import java.time.Instant;

import io.github.lvoxx.common_core.util.UlidGenerator;

public record ApiMeta(
        String requestId,
        Instant timestamp,
        String version) {
    public static ApiMeta now() {
        return new ApiMeta(UlidGenerator.generate(), Instant.now(), "v1");
    }
}
