package io.github.lvoxx.user_service.service.impl;

import java.time.Instant;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import io.github.lvoxx.common_core.exception.ConflictException;
import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_keys.CacheKeys;
import io.github.lvoxx.common_keys.LockKeys;
import io.github.lvoxx.redis_starter.service.LockService;
import io.github.lvoxx.user_service.entity.UserBlock;
import io.github.lvoxx.user_service.repository.BlockRepository;
import io.github.lvoxx.user_service.service.BlockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlockServiceImpl implements BlockService {

    private final BlockRepository blockRepo;
    private final LockService lockService;

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    @CacheEvict(value = CacheKeys.UserService.BLOCK_STATUS,
            key = "#blockerId + ':' + #blockedId")
    public Mono<Void> block(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) {
            return Mono.error(new ConflictException("CANNOT_BLOCK_SELF"));
        }
        return lockService.withLock(LockKeys.block(blockerId, blockedId), () ->
                blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)
                        .flatMap(exists -> {
                            if (exists) {
                                return Mono.error(new ConflictException("ALREADY_BLOCKED"));
                            }
                            return blockRepo.save(UserBlock.builder()
                                            .blockerId(blockerId)
                                            .blockedId(blockedId)
                                            .createdAt(Instant.now())
                                            .build())
                                    .then();
                        }));
    }

    @Override
    @CacheEvict(value = CacheKeys.UserService.BLOCK_STATUS,
            key = "#blockerId + ':' + #blockedId")
    public Mono<Void> unblock(UUID blockerId, UUID blockedId) {
        return lockService.withLock(LockKeys.block(blockerId, blockedId), () ->
                blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)
                        .flatMap(exists -> {
                            if (!exists) {
                                return Mono.error(
                                        new ResourceNotFoundException("BLOCK_NOT_FOUND", blockedId));
                            }
                            return blockRepo.deleteByBlockerIdAndBlockedId(blockerId, blockedId);
                        }));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Cacheable(value = CacheKeys.UserService.BLOCK_STATUS,
            key = "#blockerId + ':' + #blockedId")
    public Mono<Boolean> isBlocked(UUID blockerId, UUID blockedId) {
        return blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId);
    }

    @Override
    public Mono<Boolean> isEitherBlocked(UUID userA, UUID userB) {
        // Reuses isBlocked() to benefit from the cache on both directions.
        return isBlocked(userA, userB)
                .flatMap(blocked -> blocked ? Mono.just(true) : isBlocked(userB, userA));
    }
}
