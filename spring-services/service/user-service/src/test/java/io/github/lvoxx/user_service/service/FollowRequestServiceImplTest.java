package io.github.lvoxx.user_service.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.lvoxx.common_core.exception.ConflictException;
import io.github.lvoxx.common_core.exception.ForbiddenException;
import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_core.exception.ValidationException;
import io.github.lvoxx.user_service.entity.Follower;
import io.github.lvoxx.user_service.entity.FollowRequest;
import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.kafka.UserEventPublisher;
import io.github.lvoxx.user_service.repository.FollowerRepository;
import io.github.lvoxx.user_service.repository.FollowRequestRepository;
import io.github.lvoxx.user_service.repository.UserRepository;
import io.github.lvoxx.user_service.service.impl.FollowRequestServiceImpl;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("FollowRequestService — create, respond and pending list")
@ExtendWith(MockitoExtension.class)
class FollowRequestServiceImplTest {

    @Mock private FollowRequestRepository followReqRepo;
    @Mock private FollowerRepository followerRepo;
    @Mock private UserRepository userRepo;
    @Mock private UserEventPublisher eventPublisher;

    private FollowRequestServiceImpl followRequestService;

    private UUID requesterId;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        followRequestService = new FollowRequestServiceImpl(
                followReqRepo, followerRepo, userRepo, eventPublisher);

        requesterId = UUID.randomUUID();
        targetId    = UUID.randomUUID();
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create: given existing PENDING request → throws ConflictException")
    void create_givenAlreadyPending_throwsConflictException() {
        when(followReqRepo.existsByRequesterIdAndTargetIdAndStatus(requesterId, targetId, "PENDING"))
                .thenReturn(Mono.just(true));

        StepVerifier.create(followRequestService.create(requesterId, targetId))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("create: given no pending request → saves new FollowRequest with PENDING status")
    void create_givenNoPendingRequest_savesFollowRequest() {
        FollowRequest saved = FollowRequest.builder()
                .id(UUID.randomUUID()).requesterId(requesterId).targetId(targetId)
                .status("PENDING").createdAt(Instant.now()).build();

        when(followReqRepo.existsByRequesterIdAndTargetIdAndStatus(requesterId, targetId, "PENDING"))
                .thenReturn(Mono.just(false));
        when(followReqRepo.save(any(FollowRequest.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(followRequestService.create(requesterId, targetId))
                .verifyComplete();

        verify(followReqRepo).save(any(FollowRequest.class));
    }

    // ── respond ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("respond: given invalid action string → throws ValidationException")
    void respond_givenInvalidAction_throwsValidationException() {
        StepVerifier.create(followRequestService.respond(targetId, UUID.randomUUID(), "INVALID"))
                .expectError(ValidationException.class)
                .verify();
    }

    @Test
    @DisplayName("respond: given unknown requestId → throws ResourceNotFoundException")
    void respond_givenRequestNotFound_throwsResourceNotFoundException() {
        UUID reqId = UUID.randomUUID();
        when(followReqRepo.findById(reqId)).thenReturn(Mono.empty());

        StepVerifier.create(followRequestService.respond(targetId, reqId, "APPROVE"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("respond: given different targetUserId than request target → throws ForbiddenException")
    void respond_givenWrongTarget_throwsForbiddenException() {
        UUID reqId        = UUID.randomUUID();
        UUID wrongTarget  = UUID.randomUUID();
        FollowRequest req = FollowRequest.builder()
                .id(reqId).requesterId(requesterId).targetId(targetId).status("PENDING").build();

        when(followReqRepo.findById(reqId)).thenReturn(Mono.just(req));

        StepVerifier.create(followRequestService.respond(wrongTarget, reqId, "APPROVE"))
                .expectError(ForbiddenException.class)
                .verify();
    }

    @Test
    @DisplayName("respond: given already actioned request (APPROVED) → throws ConflictException")
    void respond_givenAlreadyActioned_throwsConflictException() {
        UUID reqId        = UUID.randomUUID();
        FollowRequest req = FollowRequest.builder()
                .id(reqId).requesterId(requesterId).targetId(targetId).status("APPROVED").build();

        when(followReqRepo.findById(reqId)).thenReturn(Mono.just(req));

        StepVerifier.create(followRequestService.respond(targetId, reqId, "APPROVE"))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("respond: given APPROVE action → creates Follower, increments counts, publishes event")
    void respond_givenApproveAction_createsFollowerIncrementCountsPublishesEvent() {
        UUID reqId = UUID.randomUUID();
        FollowRequest req = FollowRequest.builder()
                .id(reqId).requesterId(requesterId).targetId(targetId).status("PENDING").build();
        Follower savedFollower = Follower.builder()
                .followerId(requesterId).followingId(targetId).createdAt(Instant.now()).build();

        when(followReqRepo.findById(reqId)).thenReturn(Mono.just(req));
        when(followerRepo.save(any(Follower.class))).thenReturn(Mono.just(savedFollower));
        when(userRepo.incrementFollowerCount(targetId, 1)).thenReturn(Mono.empty());
        when(userRepo.incrementFollowingCount(requesterId, 1)).thenReturn(Mono.empty());
        when(followReqRepo.save(any(FollowRequest.class))).thenReturn(Mono.just(req));
        when(eventPublisher.publishFollowed(requesterId, targetId, "")).thenReturn(Mono.empty());

        StepVerifier.create(followRequestService.respond(targetId, reqId, "APPROVE"))
                .verifyComplete();

        verify(followerRepo).save(any(Follower.class));
        verify(userRepo).incrementFollowerCount(targetId, 1);
        verify(userRepo).incrementFollowingCount(requesterId, 1);
        verify(eventPublisher).publishFollowed(requesterId, targetId, "");
    }

    @Test
    @DisplayName("respond: given REJECT action → updates status to REJECTED, does not create Follower")
    void respond_givenRejectAction_updatesStatusOnly() {
        UUID reqId = UUID.randomUUID();
        FollowRequest req = FollowRequest.builder()
                .id(reqId).requesterId(requesterId).targetId(targetId).status("PENDING").build();

        when(followReqRepo.findById(reqId)).thenReturn(Mono.just(req));
        when(followReqRepo.save(any(FollowRequest.class))).thenReturn(Mono.just(req));

        StepVerifier.create(followRequestService.respond(targetId, reqId, "REJECT"))
                .verifyComplete();

        verify(followerRepo, never()).save(any());
        verify(eventPublisher, never()).publishFollowed(any(), any(), any());
        verify(followReqRepo).save(argThat(r -> "REJECTED".equals(r.getStatus())));
    }

    // ── getPending ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getPending: given requester exists → returns mapped FollowRequestResponse with username")
    void getPending_givenRequesterExists_returnsMappedFollowRequestResponse() {
        FollowRequest req = FollowRequest.builder()
                .id(UUID.randomUUID()).requesterId(requesterId).targetId(targetId)
                .status("PENDING").createdAt(Instant.now()).build();
        User requesterUser = User.builder()
                .id(requesterId).username("bob").avatarUrl("http://example.com/bob.jpg").build();

        when(followReqRepo.findByTargetIdAndStatus(targetId, "PENDING")).thenReturn(Flux.just(req));
        when(userRepo.findByIdAndIsDeletedFalse(requesterId)).thenReturn(Mono.just(requesterUser));

        StepVerifier.create(followRequestService.getPending(targetId))
                .expectNextMatches(page -> page.items().size() == 1
                        && "bob".equals(page.items().get(0).requesterUsername()))
                .verifyComplete();
    }

    @Test
    @DisplayName("getPending: given deleted requester → shows '[deleted]' placeholder username")
    void getPending_givenDeletedRequester_showsDeletedPlaceholder() {
        FollowRequest req = FollowRequest.builder()
                .id(UUID.randomUUID()).requesterId(requesterId).targetId(targetId)
                .status("PENDING").createdAt(Instant.now()).build();

        when(followReqRepo.findByTargetIdAndStatus(targetId, "PENDING")).thenReturn(Flux.just(req));
        when(userRepo.findByIdAndIsDeletedFalse(requesterId)).thenReturn(Mono.empty());

        StepVerifier.create(followRequestService.getPending(targetId))
                .expectNextMatches(page -> page.items().size() == 1
                        && "[deleted]".equals(page.items().get(0).requesterUsername()))
                .verifyComplete();
    }
}
