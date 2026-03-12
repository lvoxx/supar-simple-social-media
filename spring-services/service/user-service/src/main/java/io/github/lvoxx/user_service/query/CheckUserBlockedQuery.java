package io.github.lvoxx.user_service.query;

public record CheckUserBlockedQuery(String requesterId, String targetId) {
}
