package io.github.lvoxx.user_service.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.user_service.dto.UpdateUserSettingsRequest;
import io.github.lvoxx.user_service.entity.UserSettings;
import io.github.lvoxx.user_service.repository.UserSettingsRepository;
import io.github.lvoxx.user_service.service.impl.UserSettingsServiceImpl;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("UserSettingsService — read, defaults and update settings")
@ExtendWith(MockitoExtension.class)
class UserSettingsServiceImplTest {

    @Mock
    private UserSettingsRepository settingsRepo;

    private UserSettingsServiceImpl userSettingsService;

    private UUID userId;
    private UserSettings existingSettings;

    @BeforeEach
    void setUp() {
        userSettingsService = new UserSettingsServiceImpl(settingsRepo);

        userId = UUID.randomUUID();
        existingSettings = UserSettings.builder()
                .userId(userId)
                .readReceipts(false)
                .onlineStatus(true)
                .notificationLevel("MENTIONS")
                .build();
    }

    // ── getOrDefault ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrDefault: given settings exist → returns stored settings")
    void getOrDefault_givenSettingsExist_returnsStoredSettings() {
        when(settingsRepo.findByUserId(userId)).thenReturn(Mono.just(existingSettings));

        StepVerifier.create(userSettingsService.getOrDefault(userId))
                .expectNextMatches(r -> !r.readReceipts()
                        && r.onlineStatus()
                        && "MENTIONS".equals(r.notificationLevel()))
                .verifyComplete();
    }

    @Test
    @DisplayName("getOrDefault: given no settings → returns defaults with ALL notification level")
    void getOrDefault_givenNoSettings_returnsDefaults() {
        when(settingsRepo.findByUserId(userId)).thenReturn(Mono.empty());

        StepVerifier.create(userSettingsService.getOrDefault(userId))
                .expectNextMatches(r -> r.readReceipts()
                        && r.onlineStatus()
                        && "ALL".equals(r.notificationLevel())
                        && r.userId().equals(userId))
                .verifyComplete();
    }

    // ── getSettings ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSettings: given settings exist → returns UserSettingsResponse")
    void getSettings_givenSettingsExist_returnsResponse() {
        when(settingsRepo.findByUserId(userId)).thenReturn(Mono.just(existingSettings));

        StepVerifier.create(userSettingsService.getSettings(userId))
                .expectNextMatches(r -> r.userId().equals(userId))
                .verifyComplete();
    }

    @Test
    @DisplayName("getSettings: given no settings → throws ResourceNotFoundException")
    void getSettings_givenNoSettings_throwsResourceNotFoundException() {
        when(settingsRepo.findByUserId(userId)).thenReturn(Mono.empty());

        StepVerifier.create(userSettingsService.getSettings(userId))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ── initDefaults ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("initDefaults: given settings already exist → returns existing without calling save")
    void initDefaults_givenSettingsAlreadyExist_returnsExistingSettings() {
        when(settingsRepo.existsById(userId)).thenReturn(Mono.just(true));
        when(settingsRepo.findByUserId(userId)).thenReturn(Mono.just(existingSettings));

        StepVerifier.create(userSettingsService.initDefaults(userId))
                .expectNextMatches(r -> "MENTIONS".equals(r.notificationLevel()))
                .verifyComplete();

        verify(settingsRepo, never()).save(any());
    }

    @Test
    @DisplayName("initDefaults: given no settings → creates and saves default settings")
    void initDefaults_givenNoSettings_createsDefaultSettings() {
        UserSettings defaultSettings = UserSettings.builder()
                .userId(userId)
                .readReceipts(true)
                .onlineStatus(true)
                .notificationLevel("ALL")
                .build();

        when(settingsRepo.existsById(userId)).thenReturn(Mono.just(false));
        when(settingsRepo.save(any(UserSettings.class))).thenReturn(Mono.just(defaultSettings));

        StepVerifier.create(userSettingsService.initDefaults(userId))
                .expectNextMatches(r -> r.readReceipts()
                        && r.onlineStatus()
                        && "ALL".equals(r.notificationLevel()))
                .verifyComplete();

        verify(settingsRepo).save(any(UserSettings.class));
    }

    // ── updateSettings ────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateSettings: given partial request → updates only provided non-null fields")
    void updateSettings_givenPartialRequest_updatesOnlyProvidedFields() {
        UserSettings saved = UserSettings.builder()
                .userId(userId)
                .readReceipts(true)
                .onlineStatus(true)
                .notificationLevel("MENTIONS")
                .build();

        when(settingsRepo.findByUserId(userId)).thenReturn(Mono.just(existingSettings));
        when(settingsRepo.save(any(UserSettings.class))).thenReturn(Mono.just(saved));

        UpdateUserSettingsRequest req = new UpdateUserSettingsRequest(true, null, "MENTIONS");

        StepVerifier.create(userSettingsService.updateSettings(userId, req))
                .expectNextMatches(r -> r.readReceipts() && "MENTIONS".equals(r.notificationLevel()))
                .verifyComplete();

        verify(settingsRepo).save(argThat(s ->
                Boolean.TRUE.equals(s.getReadReceipts())
                        && "MENTIONS".equals(s.getNotificationLevel())));
    }

    @Test
    @DisplayName("updateSettings: given all-null request fields → saves settings unchanged")
    void updateSettings_givenAllNullFields_doesNotModifySettings() {
        when(settingsRepo.findByUserId(userId)).thenReturn(Mono.just(existingSettings));
        when(settingsRepo.save(any(UserSettings.class))).thenReturn(Mono.just(existingSettings));

        UpdateUserSettingsRequest req = new UpdateUserSettingsRequest(null, null, null);

        StepVerifier.create(userSettingsService.updateSettings(userId, req))
                .expectNextCount(1)
                .verifyComplete();

        // Settings unchanged since all fields are null
        verify(settingsRepo).save(argThat(s ->
                !s.getReadReceipts()
                        && Boolean.TRUE.equals(s.getOnlineStatus())
                        && "MENTIONS".equals(s.getNotificationLevel())));
    }

    @Test
    @DisplayName("updateSettings: given no existing settings → creates from defaults then applies changes")
    void updateSettings_givenNoExistingSettings_createsFromDefaults() {
        UserSettings defaultWithUpdate = UserSettings.builder()
                .userId(userId)
                .readReceipts(false)
                .onlineStatus(true)
                .notificationLevel("ALL")
                .build();

        when(settingsRepo.findByUserId(userId)).thenReturn(Mono.empty());
        when(settingsRepo.save(any(UserSettings.class))).thenReturn(Mono.just(defaultWithUpdate));

        UpdateUserSettingsRequest req = new UpdateUserSettingsRequest(false, null, null);

        StepVerifier.create(userSettingsService.updateSettings(userId, req))
                .expectNextCount(1)
                .verifyComplete();
    }
}
