package io.github.lvoxx.user_service.repository;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.entity.UserSettings;
import io.github.lvoxx.user_service.testcontainers.AbstractDatabaseTestContainer;
import reactor.test.StepVerifier;

@ActiveProfiles("test")
class UserSettingsRepositoryIT extends AbstractDatabaseTestContainer {

        @Autowired
        private UserSettingsRepository userSettingsRepository;

        @Autowired
        private UserRepository userRepository;

        private UUID userId;

        @BeforeEach
        void setUp() {
                User user = User.builder()
                                .id(UUID.randomUUID())
                                .keycloakId(UUID.randomUUID())
                                .username("settings_user_" + UUID.randomUUID().toString().substring(0, 8))
                                .displayName("Settings User")
                                .isVerified(false)
                                .isPrivate(false)
                                .followerCount(0)
                                .followingCount(0)
                                .postCount(0)
                                .role("USER")
                                .status("ACTIVE")
                                .isDeleted(false)
                                .build();
                userId = userRepository.save(user).block().getId();

                userSettingsRepository.save(UserSettings.builder()
                                .userId(userId)
                                .readReceipts(true)
                                .onlineStatus(false)
                                .notificationLevel("MENTIONS")
                                .build()).block();
        }

        // ── findByUserId ──────────────────────────────────────────────────────────

        @Test
        void findByUserId_givenExistingSettings_returnsSettings() {
                StepVerifier.create(userSettingsRepository.findByUserId(userId))
                                .expectNextMatches(s -> s.getUserId().equals(userId)
                                                && Boolean.TRUE.equals(s.getReadReceipts())
                                                && Boolean.FALSE.equals(s.getOnlineStatus())
                                                && "MENTIONS".equals(s.getNotificationLevel()))
                                .verifyComplete();
        }

        @Test
        void findByUserId_givenNonExistingUserId_returnsEmpty() {
                StepVerifier.create(userSettingsRepository.findByUserId(UUID.randomUUID()))
                                .verifyComplete();
        }
}
