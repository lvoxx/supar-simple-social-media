package io.github.lvoxx.user_service.service;

import java.util.UUID;

import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.user_service.dto.FollowRequestResponse;
import reactor.core.publisher.Mono;

public interface FollowRequestService {

    /**
     * Creates a PENDING follow request from {@code requesterId} to {@code targetId}.
     * 409 if a PENDING request already exists between the pair.
     * Evicts the pending-requests cache for the target user.
     */
    Mono<Void> create(UUID requesterId, UUID targetId);

    /**
     * Returns all PENDING follow requests targeting {@code targetUserId}.
     * Result is cached; evicted when a request is created or responded to.
     */
    Mono<PageResponse<FollowRequestResponse>> getPending(UUID targetUserId);

    /**
     * Approves or rejects a PENDING follow request owned by {@code targetUserId}.
     * {@code action} must be {@code "APPROVE"} or {@code "REJECT"} (case-insensitive).
     * 404 if the request is not found, 403 if the caller is not the target,
     * 409 if the request is not in PENDING state, 422 for invalid action.
     * On APPROVE: creates the Follower row and updates follower/following counts.
     */
    Mono<Void> respond(UUID targetUserId, UUID requestId, String action);
}
