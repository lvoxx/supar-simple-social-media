package io.github.lvoxx.user_service.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.lvoxx.common_core.exception.ConflictException;
import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.redis_starter.service.LockService;
import io.github.lvoxx.user_service.entity.UserBlock;
import io.github.lvoxx.user_service.repository.BlockRepository;
import io.github.lvoxx.user_service.service.impl.BlockServiceImpl;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("BlockService — block/unblock and bidirectional status checks")
@ExtendWith(MockitoExtension.class)
class BlockServiceImplTest {

    @Mock private BlockRepository blockRepo;
    @Mock private LockService lockService;

    private BlockServiceImpl blockService;

    private UUID blockerId;
    private UUID blockedId;

    @BeforeEach
    void setUp() {
        blockService = new BlockServiceImpl(blockRepo, lockService);

        blockerId = UUID.randomUUID();
        blockedId = UUID.randomUUID();

        // Lock executes the supplier immediately
        when(lockService.withLock(anyString(), any()))
                .thenAnswer(inv -> ((Supplier<Mono<?>>) inv.getArgument(1)).get());
    }

    // ── block ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("block: given self as target → throws ConflictException without querying DB")
    void block_givenSelfBlock_throwsConflictException() {
        StepVerifier.create(blockService.block(blockerId, blockerId))
                .expectError(ConflictException.class)
                .verify();

        verify(blockRepo, never()).existsByBlockerIdAndBlockedId(any(), any());
    }

    @Test
    @DisplayName("block: given already-blocked user → throws ConflictException")
    void block_givenAlreadyBlocked_throwsConflictException() {
        when(blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId))
                .thenReturn(Mono.just(true));

        StepVerifier.create(blockService.block(blockerId, blockedId))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("block: given new block → saves UserBlock entity")
    void block_givenValidRequest_savesBlock() {
        UserBlock saved = UserBlock.builder()
                .blockerId(blockerId).blockedId(blockedId).createdAt(Instant.now()).build();

        when(blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)).thenReturn(Mono.just(false));
        when(blockRepo.save(any(UserBlock.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(blockService.block(blockerId, blockedId))
                .verifyComplete();

        verify(blockRepo).save(any(UserBlock.class));
    }

    // ── unblock ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unblock: given no existing block → throws ResourceNotFoundException")
    void unblock_givenBlockNotFound_throwsResourceNotFoundException() {
        when(blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId))
                .thenReturn(Mono.just(false));

        StepVerifier.create(blockService.unblock(blockerId, blockedId))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("unblock: given existing block → deletes the block row")
    void unblock_givenExistingBlock_deletesBlock() {
        when(blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)).thenReturn(Mono.just(true));
        when(blockRepo.deleteByBlockerIdAndBlockedId(blockerId, blockedId)).thenReturn(Mono.empty());

        StepVerifier.create(blockService.unblock(blockerId, blockedId))
                .verifyComplete();

        verify(blockRepo).deleteByBlockerIdAndBlockedId(blockerId, blockedId);
    }

    // ── isBlocked ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isBlocked: given block exists → returns true")
    void isBlocked_givenBlockExists_returnsTrue() {
        when(blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)).thenReturn(Mono.just(true));

        StepVerifier.create(blockService.isBlocked(blockerId, blockedId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("isBlocked: given no block → returns false")
    void isBlocked_givenNoBlock_returnsFalse() {
        when(blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)).thenReturn(Mono.just(false));

        StepVerifier.create(blockService.isBlocked(blockerId, blockedId))
                .expectNext(false)
                .verifyComplete();
    }

    // ── isEitherBlocked ───────────────────────────────────────────────────────

    @Test
    @DisplayName("isEitherBlocked: given A blocks B → returns true without checking reverse")
    void isEitherBlocked_givenABlocksB_returnsTrue() {
        when(blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)).thenReturn(Mono.just(true));

        StepVerifier.create(blockService.isEitherBlocked(blockerId, blockedId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("isEitherBlocked: given only B blocks A → returns true")
    void isEitherBlocked_givenBBlocksA_returnsTrue() {
        when(blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)).thenReturn(Mono.just(false));
        when(blockRepo.existsByBlockerIdAndBlockedId(blockedId, blockerId)).thenReturn(Mono.just(true));

        StepVerifier.create(blockService.isEitherBlocked(blockerId, blockedId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("isEitherBlocked: given neither blocks the other → returns false")
    void isEitherBlocked_givenNeitherBlocks_returnsFalse() {
        when(blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)).thenReturn(Mono.just(false));
        when(blockRepo.existsByBlockerIdAndBlockedId(blockedId, blockerId)).thenReturn(Mono.just(false));

        StepVerifier.create(blockService.isEitherBlocked(blockerId, blockedId))
                .expectNext(false)
                .verifyComplete();
    }
}
