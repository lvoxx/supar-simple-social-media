package io.github.lvoxx.user_service.dto;

public record UpdateUserSettingsRequest(
        Boolean readReceipts,
        Boolean onlineStatus,
        String notificationLevel) {
}
