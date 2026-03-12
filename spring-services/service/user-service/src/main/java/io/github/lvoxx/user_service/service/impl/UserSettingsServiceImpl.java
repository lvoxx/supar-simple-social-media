package io.github.lvoxx.user_service.service.impl;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_keys.CacheKeys;
import io.github.lvoxx.user_service.dto.UpdateUserSettingsRequest;
import io.github.lvoxx.user_service.dto.UserSettingsResponse;
import io.github.lvoxx.user_service.entity.UserSettings;
import io.github.lvoxx.user_service.repository.UserSettingsRepository;
import io.github.lvoxx.user_service.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSettingsServiceImpl implements UserSettingsService {

    private final UserSettingsRepository settingsRepo;

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Cacheable(value = CacheKeys.UserService.USER_SETTINGS_KEY, key = "#userId")
    public Mono<UserSettingsResponse> getOrDefault(UUID userId) {
        return settingsRepo.findByUserId(userId)
                .map(this::toResponse)
                .defaultIfEmpty(defaultResponse(userId));
    }

    @Override
    @Cacheable(value = CacheKeys.UserService.USER_SETTINGS_KEY, key = "#userId")
    public Mono<UserSettingsResponse> getSettings(UUID userId) {
        return settingsRepo.findByUserId(userId)
                .map(this::toResponse)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException("USER_SETTINGS_NOT_FOUND", userId)));
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    public Mono<UserSettingsResponse> initDefaults(UUID userId) {
        return settingsRepo.existsById(userId)
                .flatMap(exists -> exists
                        ? settingsRepo.findByUserId(userId).map(this::toResponse)
                        : settingsRepo.save(defaultEntity(userId)).map(this::toResponse));
    }

    @Override
    @CacheEvict(value = CacheKeys.UserService.USER_SETTINGS_KEY, key = "#userId")
    public Mono<UserSettingsResponse> updateSettings(UUID userId, UpdateUserSettingsRequest req) {
        return settingsRepo.findByUserId(userId)
                .defaultIfEmpty(defaultEntity(userId))
                .flatMap(settings -> {
                    if (req.readReceipts() != null)
                        settings.setReadReceipts(req.readReceipts());
                    if (req.onlineStatus() != null)
                        settings.setOnlineStatus(req.onlineStatus());
                    if (req.notificationLevel() != null)
                        settings.setNotificationLevel(req.notificationLevel());
                    return settingsRepo.save(settings);
                })
                .map(this::toResponse);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserSettings defaultEntity(UUID userId) {
        return UserSettings.builder()
                .userId(userId)
                .readReceipts(true)
                .onlineStatus(true)
                .notificationLevel("ALL")
                .build();
    }

    private UserSettingsResponse defaultResponse(UUID userId) {
        return new UserSettingsResponse(userId, true, true, "ALL");
    }

    private UserSettingsResponse toResponse(UserSettings s) {
        return new UserSettingsResponse(
                s.getUserId(),
                Boolean.TRUE.equals(s.getReadReceipts()),
                Boolean.TRUE.equals(s.getOnlineStatus()),
                s.getNotificationLevel() != null ? s.getNotificationLevel() : "ALL");
    }
}
