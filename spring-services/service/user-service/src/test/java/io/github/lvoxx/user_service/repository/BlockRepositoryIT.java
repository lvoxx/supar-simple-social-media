package io.github.lvoxx.user_service.repository;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.entity.UserBlock;
import io.github.lvoxx.user_service.testcontainers.AbstractDatabaseTestContainer;
import reactor.test.StepVerifier;

@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BlockRepositoryIT extends AbstractDatabaseTestContainer {

    @Autowired
    private BlockRepository blockRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID blockerId;
    private UUID blockedId;

    @BeforeEach
    void setUp() {
        blockerId = createUser("blocker_" + UUID.randomUUID().toString().substring(0, 8));
        blockedId = createUser("blocked_" + UUID.randomUUID().toString().substring(0, 8));

        blockRepository.save(UserBlock.builder()
                .blockerId(blockerId)
                .blockedId(blockedId)
                .createdAt(Instant.now())
                .build()).block();
    }

    private UUID createUser(String username) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .keycloakId(UUID.randomUUID())
                .username(username)
                .displayName("Test")
                .isVerified(false)
                .isPrivate(false)
                .followerCount(0)
                .followingCount(0)
                .postCount(0)
                .role("USER")
                .status("ACTIVE")
                .isDeleted(false)
                .build();
        return userRepository.save(user).block().getId();
    }

    // ── existsByBlockerIdAndBlockedId ─────────────────────────────────────────

    @Test
    void existsByBlockerIdAndBlockedId_givenExistingBlock_returnsTrue() {
        StepVerifier.create(blockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void existsByBlockerIdAndBlockedId_givenNoBlock_returnsFalse() {
        StepVerifier.create(blockRepository.existsByBlockerIdAndBlockedId(blockedId, blockerId))
                .expectNext(false)
                .verifyComplete();
    }

    // ── deleteByBlockerIdAndBlockedId ─────────────────────────────────────────

    @Test
    void deleteByBlockerIdAndBlockedId_givenExistingRow_removesRow() {
        StepVerifier.create(blockRepository.deleteByBlockerIdAndBlockedId(blockerId, blockedId))
                .verifyComplete();

        StepVerifier.create(blockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId))
                .expectNext(false)
                .verifyComplete();
    }
}
