package io.github.lvoxx.user_service.dto;

import java.util.Map;

public record UpdateSettingsRequest(
        Map<String, Object> themeSettings,
        Map<String, Object> notificationSettings,
        Map<String, Object> accountSettings) {
}
