package io.github.lvoxx.user_service.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.lvoxx.common_core.enums.UserRole;
import io.github.lvoxx.common_core.exception.ConflictException;
import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.common_core.security.UserPrincipal;
import io.github.lvoxx.user_service.dto.AccountHistoryResponse;
import io.github.lvoxx.user_service.dto.FollowRequestResponse;
import io.github.lvoxx.user_service.dto.UpdateProfileRequest;
import io.github.lvoxx.user_service.dto.UpdateSettingsRequest;
import io.github.lvoxx.user_service.dto.UserResponse;
import io.github.lvoxx.user_service.dto.VerificationRequest;
import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.entity.Verification;
import io.github.lvoxx.user_service.kafka.UserEventPublisher;
import io.github.lvoxx.user_service.repository.UserRepository;
import io.github.lvoxx.user_service.repository.VerificationRepository;
import io.github.lvoxx.user_service.service.impl.UserServiceImpl;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("UserService — profile, social graph, search and verification")
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserRepository userRepo;
    @Mock private VerificationRepository verificationRepo;
    @Mock private UserEventPublisher eventPublisher;
    @Mock private FollowerService followerService;
    @Mock private FollowRequestService followRequestService;
    @Mock private AccountHistoryService historyService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private UserServiceImpl userService;

    private UUID userId;
    private UUID targetId;
    private UserPrincipal principal;
    private User testUser;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userRepo, verificationRepo, eventPublisher,
                objectMapper, followerService, followRequestService, historyService);

        userId   = UUID.randomUUID();
        targetId = UUID.randomUUID();
        principal = new UserPrincipal(userId, "alice", Set.of(UserRole.USER), "127.0.0.1");

        testUser = User.builder()
                .id(userId).username("alice").displayName("Alice")
                .isPrivate(false).isVerified(false)
                .followerCount(0).followingCount(0).postCount(0)
                .role("USER").build();
    }

    // ── getByUsername ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getByUsername: given existing username → returns UserResponse")
    void getByUsername_givenExistingUsername_returnsUserResponse() {
        when(userRepo.findByUsernameAndIsDeletedFalse("alice")).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.getByUsername("alice"))
                .expectNextMatches(r -> "alice".equals(r.username()))
                .verifyComplete();
    }

    @Test
    @DisplayName("getByUsername: given unknown username → throws ResourceNotFoundException")
    void getByUsername_givenNonExistingUsername_throwsResourceNotFoundException() {
        when(userRepo.findByUsernameAndIsDeletedFalse("unknown")).thenReturn(Mono.empty());

        StepVerifier.create(userService.getByUsername("unknown"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getById: given existing id → returns UserResponse")
    void getById_givenExistingId_returnsUserResponse() {
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.getById(userId))
                .expectNextMatches(r -> r.id().equals(userId))
                .verifyComplete();
    }

    @Test
    @DisplayName("getById: given non-existing id → throws ResourceNotFoundException")
    void getById_givenNonExistingId_throwsResourceNotFoundException() {
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.getById(userId))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ── updateProfile ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateProfile: given partial request → updates only non-null fields")
    void updateProfile_givenPartialUpdate_updatesOnlyNonNullFields() {
        UpdateProfileRequest req = new UpdateProfileRequest("NewName", null, null, null, null, null);
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.just(testUser));
        when(userRepo.save(any(User.class))).thenReturn(Mono.just(testUser));
        when(eventPublisher.publishProfileUpdated(any())).thenReturn(Mono.empty());

        StepVerifier.create(userService.updateProfile(principal, req))
                .expectNextMatches(r -> r.username().equals("alice"))
                .verifyComplete();

        verify(userRepo).save(argThat(u -> "NewName".equals(u.getDisplayName())));
    }

    @Test
    @DisplayName("updateProfile: given user not found → throws ResourceNotFoundException")
    void updateProfile_givenNonExistingUser_throwsResourceNotFoundException() {
        UpdateProfileRequest req = new UpdateProfileRequest("NewName", null, null, null, null, null);
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.updateProfile(principal, req))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ── updateAvatar ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateAvatar: given valid user → publishes user.avatar.changed event")
    void updateAvatar_givenValidUser_publishesAvatarChangedEvent() {
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.just(testUser));
        when(userRepo.save(any(User.class))).thenReturn(Mono.just(testUser));
        when(eventPublisher.publishAvatarChanged(any())).thenReturn(Mono.empty());

        StepVerifier.create(userService.updateAvatar(principal, "media-id-123"))
                .expectNextMatches(r -> r.id().equals(userId))
                .verifyComplete();

        verify(eventPublisher).publishAvatarChanged(any(User.class));
    }

    @Test
    @DisplayName("updateAvatar: given user not found → throws ResourceNotFoundException")
    void updateAvatar_givenNonExistingUser_throwsResourceNotFoundException() {
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.updateAvatar(principal, "media-id-123"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ── updateBackground ──────────────────────────────────────────────────────

    @Test
    @DisplayName("updateBackground: given valid user → publishes user.background.changed event")
    void updateBackground_givenValidUser_publishesBackgroundChangedEvent() {
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.just(testUser));
        when(userRepo.save(any(User.class))).thenReturn(Mono.just(testUser));
        when(eventPublisher.publishBackgroundChanged(any())).thenReturn(Mono.empty());

        StepVerifier.create(userService.updateBackground(principal, "bg-media-id"))
                .expectNextMatches(r -> r.id().equals(userId))
                .verifyComplete();

        verify(eventPublisher).publishBackgroundChanged(any(User.class));
    }

    @Test
    @DisplayName("updateBackground: given user not found → throws ResourceNotFoundException")
    void updateBackground_givenNonExistingUser_throwsResourceNotFoundException() {
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.updateBackground(principal, "bg-media-id"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ── updateSettings ────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateSettings: given valid JSON map → serialises and saves settings")
    void updateSettings_givenValidJson_updatesUserSettings() {
        UpdateSettingsRequest req = new UpdateSettingsRequest(
                java.util.Map.of("theme", "dark"), null, null);
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.just(testUser));
        when(userRepo.save(any(User.class))).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.updateSettings(principal, req))
                .expectNextMatches(r -> r.id().equals(userId))
                .verifyComplete();
    }

    // ── follow ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("follow: given self as target → throws ConflictException")
    void follow_givenSelfFollow_throwsConflictException() {
        StepVerifier.create(userService.follow(principal, userId))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("follow: given non-existing target → throws ResourceNotFoundException")
    void follow_givenNonExistingTarget_throwsResourceNotFoundException() {
        when(userRepo.findByIdAndIsDeletedFalse(targetId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.follow(principal, targetId))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("follow: given public target → delegates to FollowerService.follow")
    void follow_givenPublicUser_delegatesToFollowerService() {
        User publicTarget = User.builder().id(targetId).username("bob").isPrivate(false).build();
        when(userRepo.findByIdAndIsDeletedFalse(targetId)).thenReturn(Mono.just(publicTarget));
        when(followerService.follow(userId, targetId, "alice")).thenReturn(Mono.empty());

        StepVerifier.create(userService.follow(principal, targetId))
                .verifyComplete();

        verify(followerService).follow(userId, targetId, "alice");
        verify(followRequestService, never()).create(any(), any());
    }

    @Test
    @DisplayName("follow: given private target → creates follow request instead of direct follow")
    void follow_givenPrivateUser_delegatesToFollowRequestService() {
        User privateTarget = User.builder().id(targetId).username("charlie").isPrivate(true).build();
        when(userRepo.findByIdAndIsDeletedFalse(targetId)).thenReturn(Mono.just(privateTarget));
        when(followRequestService.create(userId, targetId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.follow(principal, targetId))
                .verifyComplete();

        verify(followRequestService).create(userId, targetId);
        verify(followerService, never()).follow(any(), any(), any());
    }

    // ── unfollow ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unfollow: given valid request → delegates to FollowerService.unfollow")
    void unfollow_givenValidRequest_delegatesToFollowerService() {
        when(followerService.unfollow(userId, targetId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.unfollow(principal, targetId))
                .verifyComplete();

        verify(followerService).unfollow(userId, targetId);
    }

    // ── getFollowers / getFollowing ───────────────────────────────────────────

    @Test
    @DisplayName("getFollowers: delegates to FollowerService and returns PageResponse")
    void getFollowers_givenUserId_delegatesToFollowerService() {
        PageResponse<UserResponse> page = PageResponse.empty();
        when(followerService.getFollowers(userId, null, 20)).thenReturn(Mono.just(page));

        StepVerifier.create(userService.getFollowers(userId, null, 20))
                .expectNext(page)
                .verifyComplete();
    }

    @Test
    @DisplayName("getFollowing: delegates to FollowerService and returns PageResponse")
    void getFollowing_givenUserId_delegatesToFollowerService() {
        PageResponse<UserResponse> page = PageResponse.empty();
        when(followerService.getFollowing(userId, null, 20)).thenReturn(Mono.just(page));

        StepVerifier.create(userService.getFollowing(userId, null, 20))
                .expectNext(page)
                .verifyComplete();
    }

    // ── getFollowRequests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getFollowRequests: given principal → delegates to FollowRequestService.getPending")
    void getFollowRequests_givenPrincipal_delegatesToFollowRequestService() {
        PageResponse<FollowRequestResponse> page = PageResponse.empty();
        when(followRequestService.getPending(userId)).thenReturn(Mono.just(page));

        StepVerifier.create(userService.getFollowRequests(principal))
                .expectNext(page)
                .verifyComplete();
    }

    // ── respondFollowRequest ──────────────────────────────────────────────────

    @Test
    @DisplayName("respondFollowRequest: given valid args → delegates to FollowRequestService.respond")
    void respondFollowRequest_givenValidArgs_delegatesToFollowRequestService() {
        UUID reqId = UUID.randomUUID();
        when(followRequestService.respond(userId, reqId, "APPROVE")).thenReturn(Mono.empty());

        StepVerifier.create(userService.respondFollowRequest(principal, reqId, "APPROVE"))
                .verifyComplete();
    }

    // ── getHistory ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHistory: given principal → delegates to AccountHistoryService with limit 50")
    void getHistory_givenPrincipal_delegatesToHistoryService() {
        PageResponse<AccountHistoryResponse> page = PageResponse.empty();
        when(historyService.getHistory(userId, 50)).thenReturn(Mono.just(page));

        StepVerifier.create(userService.getHistory(principal))
                .expectNext(page)
                .verifyComplete();
    }

    // ── searchUsers ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("searchUsers: given query shorter than 2 chars → returns empty page without querying DB")
    void searchUsers_givenQueryLessThan2Chars_returnsEmptyPage() {
        StepVerifier.create(userService.searchUsers("a", 10))
                .expectNextMatches(page -> page.items().isEmpty())
                .verifyComplete();

        verify(userRepo, never()).searchByUsernameOrDisplayName(any(), anyInt());
    }

    @Test
    @DisplayName("searchUsers: given null query → returns empty page without querying DB")
    void searchUsers_givenNullQuery_returnsEmptyPage() {
        StepVerifier.create(userService.searchUsers(null, 10))
                .expectNextMatches(page -> page.items().isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("searchUsers: given valid 3-char query → returns mapped UserResponse list")
    void searchUsers_givenValidQuery_returnsMappedResults() {
        when(userRepo.searchByUsernameOrDisplayName("ali", 10))
                .thenReturn(Flux.just(testUser));

        StepVerifier.create(userService.searchUsers("ali", 10))
                .expectNextMatches(page -> page.items().size() == 1
                        && "alice".equals(page.items().get(0).username()))
                .verifyComplete();
    }

    // ── submitVerification ────────────────────────────────────────────────────

    @Test
    @DisplayName("submitVerification: given existing PENDING request → throws ConflictException")
    void submitVerification_givenAlreadyPending_throwsConflictException() {
        when(verificationRepo.existsByUserIdAndStatus(userId, "PENDING")).thenReturn(Mono.just(true));

        StepVerifier.create(userService.submitVerification(principal, new VerificationRequest("doc-id", "IDENTITY")))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("submitVerification: given user not found → throws ResourceNotFoundException")
    void submitVerification_givenUserNotFound_throwsResourceNotFoundException() {
        when(verificationRepo.existsByUserIdAndStatus(userId, "PENDING")).thenReturn(Mono.just(false));
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.submitVerification(principal, new VerificationRequest("doc-id", "IDENTITY")))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("submitVerification: given no pending and user exists → saves Verification entity")
    void submitVerification_givenValidRequest_savesVerification() {
        when(verificationRepo.existsByUserIdAndStatus(userId, "PENDING")).thenReturn(Mono.just(false));
        when(userRepo.findByIdAndIsDeletedFalse(userId)).thenReturn(Mono.just(testUser));
        when(verificationRepo.save(any(Verification.class)))
                .thenReturn(Mono.just(Verification.builder()
                        .id(UUID.randomUUID()).userId(userId)
                        .type("IDENTITY").status("PENDING").createdAt(Instant.now()).build()));

        StepVerifier.create(userService.submitVerification(principal, new VerificationRequest("doc-id", "IDENTITY")))
                .verifyComplete();

        verify(verificationRepo).save(any(Verification.class));
    }
}
