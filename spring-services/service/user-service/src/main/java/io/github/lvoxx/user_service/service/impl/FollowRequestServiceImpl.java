package io.github.lvoxx.user_service.service.impl;

import java.time.Instant;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.lvoxx.common_core.exception.ConflictException;
import io.github.lvoxx.common_core.exception.ForbiddenException;
import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_core.exception.ValidationException;
import io.github.lvoxx.common_core.message.MessageKeys;
import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.common_core.util.UlidGenerator;
import io.github.lvoxx.common_keys.CacheKeys;
import io.github.lvoxx.user_service.dto.FollowRequestResponse;
import io.github.lvoxx.user_service.entity.Follower;
import io.github.lvoxx.user_service.entity.FollowRequest;
import io.github.lvoxx.user_service.kafka.UserEventPublisher;
import io.github.lvoxx.user_service.repository.FollowerRepository;
import io.github.lvoxx.user_service.repository.FollowRequestRepository;
import io.github.lvoxx.user_service.repository.UserRepository;
import io.github.lvoxx.user_service.service.FollowRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class FollowRequestServiceImpl implements FollowRequestService {

    private final FollowRequestRepository followReqRepo;
    private final FollowerRepository followerRepo;
    private final UserRepository userRepo;
    private final UserEventPublisher eventPublisher;

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    @CacheEvict(value = CacheKeys.UserService.FOLLOW_REQUESTS_LIST, key = "#targetId")
    public Mono<Void> create(UUID requesterId, UUID targetId) {
        return followReqRepo.existsByRequesterIdAndTargetIdAndStatus(requesterId, targetId, "PENDING")
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(
                                new ConflictException(MessageKeys.ALREADY_FOLLOWING, targetId));
                    }
                    return followReqRepo.save(FollowRequest.builder()
                                    .id(UlidGenerator.generateAsUUID())
                                    .requesterId(requesterId)
                                    .targetId(targetId)
                                    .status("PENDING")
                                    .createdAt(Instant.now())
                                    .build())
                            .then();
                });
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheKeys.UserService.FOLLOW_REQUESTS_LIST, key = "#targetUserId")
    public Mono<Void> respond(UUID targetUserId, UUID requestId, String action) {
        if (!"APPROVE".equalsIgnoreCase(action) && !"REJECT".equalsIgnoreCase(action)) {
            return Mono.error(new ValidationException("INVALID_ACTION"));
        }
        return followReqRepo.findById(requestId)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException("FOLLOW_REQUEST_NOT_FOUND", requestId)))
                .flatMap(req -> {
                    if (!req.getTargetId().equals(targetUserId)) {
                        return Mono.error(new ForbiddenException(MessageKeys.FORBIDDEN));
                    }
                    if (!"PENDING".equals(req.getStatus())) {
                        return Mono.error(new ConflictException("REQUEST_ALREADY_ACTIONED"));
                    }
                    req.setStatus("APPROVE".equalsIgnoreCase(action) ? "APPROVED" : "REJECTED");

                    if ("APPROVE".equalsIgnoreCase(action)) {
                        return followerRepo.save(Follower.builder()
                                        .followerId(req.getRequesterId())
                                        .followingId(req.getTargetId())
                                        .createdAt(Instant.now())
                                        .build())
                                .then(userRepo.incrementFollowerCount(req.getTargetId(), 1))
                                .then(userRepo.incrementFollowingCount(req.getRequesterId(), 1))
                                .then(followReqRepo.save(req))
                                .then(eventPublisher.publishFollowed(
                                        req.getRequesterId(), req.getTargetId(), ""));
                    }
                    return followReqRepo.save(req).then();
                });
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Cacheable(value = CacheKeys.UserService.FOLLOW_REQUESTS_LIST, key = "#targetUserId")
    public Mono<PageResponse<FollowRequestResponse>> getPending(UUID targetUserId) {
        return followReqRepo.findByTargetIdAndStatus(targetUserId, "PENDING")
                .flatMap(req -> userRepo.findByIdAndIsDeletedFalse(req.getRequesterId())
                        .map(u -> new FollowRequestResponse(
                                req.getId(), req.getRequesterId(),
                                u.getUsername(), u.getAvatarUrl(),
                                req.getStatus(), req.getCreatedAt()))
                        .defaultIfEmpty(new FollowRequestResponse(
                                req.getId(), req.getRequesterId(),
                                "[deleted]", null,
                                req.getStatus(), req.getCreatedAt())))
                .collectList()
                .map(items -> PageResponse.of(items, null));
    }
}
