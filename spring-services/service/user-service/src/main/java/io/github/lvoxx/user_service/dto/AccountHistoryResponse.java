package io.github.lvoxx.user_service.dto;

import java.time.Instant;
import java.util.UUID;

public record AccountHistoryResponse(
        UUID id,
        String action,
        String detail,
        String ip,
        Instant createdAt) {
}
