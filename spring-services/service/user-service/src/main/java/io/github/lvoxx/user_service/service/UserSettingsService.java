package io.github.lvoxx.user_service.service;

import java.util.UUID;

import io.github.lvoxx.user_service.dto.UpdateUserSettingsRequest;
import io.github.lvoxx.user_service.dto.UserSettingsResponse;
import reactor.core.publisher.Mono;

public interface UserSettingsService {

    /**
     * Returns settings for the user, or in-memory defaults if no row exists yet.
     * Never throws 404 — safe for gRPC and internal callers.
     */
    Mono<UserSettingsResponse> getOrDefault(UUID userId);

    /**
     * Returns settings for the user.
     * 404 {@code ResourceNotFoundException} if no settings row exists.
     */
    Mono<UserSettingsResponse> getSettings(UUID userId);

    /**
     * Creates a default settings row for a new user. Idempotent — returns the
     * existing row if one is already present.
     */
    Mono<UserSettingsResponse> initDefaults(UUID userId);

    /**
     * Partial update: only non-null fields in the request are applied.
     * Creates the row with defaults if it does not exist yet.
     * Evicts the settings cache for this user.
     */
    Mono<UserSettingsResponse> updateSettings(UUID userId, UpdateUserSettingsRequest req);
}
