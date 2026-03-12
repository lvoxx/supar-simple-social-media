package io.github.lvoxx.user_service.dto;

import java.util.UUID;

public record UserSettingsResponse(
        UUID userId,
        boolean readReceipts,
        boolean onlineStatus,
        String notificationLevel) {
}
