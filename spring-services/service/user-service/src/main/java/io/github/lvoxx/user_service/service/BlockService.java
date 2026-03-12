package io.github.lvoxx.user_service.service;

import java.util.UUID;

import reactor.core.publisher.Mono;

public interface BlockService {

    /**
     * Blocks {@code blockedId} as seen from {@code blockerId}.
     * Acquires a distributed lock. 409 if the block already exists.
     */
    Mono<Void> block(UUID blockerId, UUID blockedId);

    /**
     * Removes the block relationship.
     * 404 if no block exists between the pair.
     */
    Mono<Void> unblock(UUID blockerId, UUID blockedId);

    /**
     * Returns {@code true} if {@code blockerId} has blocked {@code blockedId}.
     * Result is cached; invalidated on block/unblock.
     */
    Mono<Boolean> isBlocked(UUID blockerId, UUID blockedId);

    /**
     * Returns {@code true} if either user has blocked the other.
     * Delegates to two {@link #isBlocked} calls, benefiting from the cache.
     */
    Mono<Boolean> isEitherBlocked(UUID userA, UUID userB);
}
