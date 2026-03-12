package io.github.lvoxx.user_service.grpc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.proto.user.CheckUserBlockedRequest;
import io.github.lvoxx.proto.user.FindUserByIdRequest;
import io.github.lvoxx.proto.user.FindUsersByIdsRequest;
import io.github.lvoxx.proto.user.GetNotifPreferencesRequest;
import io.github.lvoxx.proto.user.GetUserSettingsRequest;
import io.github.lvoxx.user_service.dto.UserSettingsResponse;
import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.repository.UserRepository;
import io.github.lvoxx.user_service.service.BlockService;
import io.github.lvoxx.user_service.service.UserSettingsService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("UserGrpcService — gRPC method contracts")
@ExtendWith(MockitoExtension.class)
class UserGrpcServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private BlockService blockService;
    @Mock
    private UserSettingsService userSettingsService;

    private UserGrpcService userGrpcService;

    private UUID userId;
    private User testUser;

    @BeforeEach
    void setUp() {
        userGrpcService = new UserGrpcService(userRepository, blockService, userSettingsService);

        userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .username("alice")
                .displayName("Alice")
                .avatarUrl("http://example.com/alice.jpg")
                .isVerified(false)
                .isPrivate(false)
                .role("USER")
                .status("ACTIVE")
                .fcmToken("fcm-token-123")
                .apnsToken("apns-token-456")
                .pushEnabled(true)
                .emailEnabled(true)
                .createdAt(Instant.now())
                .build();
    }

    // ── findUserById ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("findUserById: given existing user → returns mapped proto response")
    void findUserById_givenExistingUser_returnsProtoResponse() {
        FindUserByIdRequest request = FindUserByIdRequest.newBuilder()
                .setUserId(userId.toString())
                .build();

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.just(testUser));

        StepVerifier.create(userGrpcService.findUserById(Mono.just(request)))
                .expectNextMatches(r -> r.getUsername().equals("alice")
                        && r.getUserId().equals(userId.toString()))
                .verifyComplete();
    }

    @Test
    @DisplayName("findUserById: given non-existing user → throws ResourceNotFoundException")
    void findUserById_givenNonExistingUser_throwsResourceNotFoundException() {
        FindUserByIdRequest request = FindUserByIdRequest.newBuilder()
                .setUserId(userId.toString())
                .build();

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.empty());

        StepVerifier.create(userGrpcService.findUserById(Mono.just(request)))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ── findUsersByIds ────────────────────────────────────────────────────────

    @Test
    void findUsersByIds_givenListOfIds_returnsUserList() {
        UUID userId2 = UUID.randomUUID();
        User user2 = User.builder()
                .id(userId2)
                .username("bob")
                .displayName("Bob")
                .isVerified(false)
                .isPrivate(false)
                .role("USER")
                .status("ACTIVE")
                .createdAt(Instant.now())
                .build();

        FindUsersByIdsRequest request = FindUsersByIdsRequest.newBuilder()
                .addAllUserIds(List.of(userId.toString(), userId2.toString()))
                .build();

        when(userRepository.findAllById(anyList()))
                .thenReturn(Flux.just(testUser, user2));

        StepVerifier.create(userGrpcService.findUsersByIds(Mono.just(request)))
                .expectNextMatches(r -> r.getUsersCount() == 2)
                .verifyComplete();
    }

    // ── checkUserBlocked ──────────────────────────────────────────────────────

    @Test
    void checkUserBlocked_givenBlocked_returnsTrue() {
        UUID blockerId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        CheckUserBlockedRequest request = CheckUserBlockedRequest.newBuilder()
                .setBlockerId(blockerId.toString())
                .setBlockedId(blockedId.toString())
                .build();

        when(blockService.isBlocked(blockerId, blockedId)).thenReturn(Mono.just(true));

        StepVerifier.create(userGrpcService.checkUserBlocked(Mono.just(request)))
                .expectNextMatches(r -> r.getIsBlocked())
                .verifyComplete();
    }

    @Test
    void checkUserBlocked_givenNotBlocked_returnsFalse() {
        UUID blockerId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        CheckUserBlockedRequest request = CheckUserBlockedRequest.newBuilder()
                .setBlockerId(blockerId.toString())
                .setBlockedId(blockedId.toString())
                .build();

        when(blockService.isBlocked(blockerId, blockedId)).thenReturn(Mono.just(false));

        StepVerifier.create(userGrpcService.checkUserBlocked(Mono.just(request)))
                .expectNextMatches(r -> !r.getIsBlocked())
                .verifyComplete();
    }

    // ── getUserSettings ───────────────────────────────────────────────────────

    @Test
    void getUserSettings_givenValidUserId_mapsSettingsCorrectly() {
        GetUserSettingsRequest request = GetUserSettingsRequest.newBuilder()
                .setUserId(userId.toString())
                .build();

        UserSettingsResponse settingsResponse = new UserSettingsResponse(userId, false, true, "MENTIONS");
        when(userSettingsService.getOrDefault(userId)).thenReturn(Mono.just(settingsResponse));

        StepVerifier.create(userGrpcService.getUserSettings(Mono.just(request)))
                .expectNextMatches(r -> !r.getReadReceipts()
                        && r.getOnlineStatus()
                        && "MENTIONS".equals(r.getNotificationLevel()))
                .verifyComplete();
    }

    // ── getNotifPreferences ───────────────────────────────────────────────────

    @Test
    void getNotifPreferences_givenUserFoundWithTokens_returnsTokensAndPreferences() {
        GetNotifPreferencesRequest request = GetNotifPreferencesRequest.newBuilder()
                .setUserId(userId.toString())
                .build();

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.just(testUser));

        StepVerifier.create(userGrpcService.getNotifPreferences(Mono.just(request)))
                .expectNextMatches(r -> "fcm-token-123".equals(r.getFcmToken())
                        && "apns-token-456".equals(r.getApnsToken())
                        && r.getPushEnabled()
                        && r.getEmailEnabled())
                .verifyComplete();
    }

    @Test
    void getNotifPreferences_givenUserNotFound_returnsDefaultsViaDefaultIfEmpty() {
        GetNotifPreferencesRequest request = GetNotifPreferencesRequest.newBuilder()
                .setUserId(userId.toString())
                .build();

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.empty());

        StepVerifier.create(userGrpcService.getNotifPreferences(Mono.just(request)))
                .expectNextMatches(r -> r.getPushEnabled()
                        && r.getEmailEnabled()
                        && r.getFcmToken().isEmpty()
                        && r.getApnsToken().isEmpty())
                .verifyComplete();
    }

    @Test
    void getNotifPreferences_givenUserWithNullTokens_returnsEmptyStringTokens() {
        User userWithNullTokens = User.builder()
                .id(userId)
                .username("alice")
                .fcmToken(null)
                .apnsToken(null)
                .pushEnabled(true)
                .emailEnabled(false)
                .createdAt(Instant.now())
                .build();

        GetNotifPreferencesRequest request = GetNotifPreferencesRequest.newBuilder()
                .setUserId(userId.toString())
                .build();

        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.just(userWithNullTokens));

        StepVerifier.create(userGrpcService.getNotifPreferences(Mono.just(request)))
                .expectNextMatches(r -> r.getFcmToken().isEmpty()
                        && r.getApnsToken().isEmpty()
                        && r.getPushEnabled()
                        && !r.getEmailEnabled())
                .verifyComplete();
    }
}
