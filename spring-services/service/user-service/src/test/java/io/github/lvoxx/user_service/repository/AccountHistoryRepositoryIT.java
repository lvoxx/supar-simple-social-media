package io.github.lvoxx.user_service.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import io.github.lvoxx.user_service.entity.AccountHistory;
import io.github.lvoxx.user_service.testcontainers.AbstractDatabaseTestContainer;
import reactor.test.StepVerifier;

@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountHistoryRepositoryIT extends AbstractDatabaseTestContainer {

    @Autowired
    private AccountHistoryRepository accountHistoryRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        Instant base = Instant.now();

        // oldest
        accountHistoryRepository.save(AccountHistory.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .action("LOGIN")
                .detail("First login")
                .ip("10.0.0.1")
                .createdAt(base.minusSeconds(120))
                .build()).block();

        // middle
        accountHistoryRepository.save(AccountHistory.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .action("PROFILE_UPDATE")
                .detail("Changed bio")
                .ip("10.0.0.1")
                .createdAt(base.minusSeconds(60))
                .build()).block();

        // newest
        accountHistoryRepository.save(AccountHistory.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .action("PASSWORD_CHANGE")
                .detail("New password set")
                .ip("10.0.0.2")
                .createdAt(base)
                .build()).block();
    }

    // ── findByUserIdOrderByCreatedAtDesc ──────────────────────────────────────

    @Test
    void findByUserIdOrderByCreatedAtDesc_givenUserId_returnsInDescOrder() {
        StepVerifier.create(accountHistoryRepository
                .findByUserIdOrderByCreatedAtDesc(userId, 50).collectList())
                .expectNextMatches(list -> {
                    assertThat(list).hasSize(3);
                    assertThat(list.get(0).getAction()).isEqualTo("PASSWORD_CHANGE");
                    assertThat(list.get(1).getAction()).isEqualTo("PROFILE_UPDATE");
                    assertThat(list.get(2).getAction()).isEqualTo("LOGIN");
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void findByUserIdOrderByCreatedAtDesc_givenLimit_respectsLimit() {
        StepVerifier.create(accountHistoryRepository
                .findByUserIdOrderByCreatedAtDesc(userId, 2).collectList())
                .expectNextMatches(list -> list.size() == 2)
                .verifyComplete();
    }

    @Test
    void findByUserIdOrderByCreatedAtDesc_givenDifferentUser_returnsEmpty() {
        StepVerifier.create(accountHistoryRepository
                .findByUserIdOrderByCreatedAtDesc(UUID.randomUUID(), 50).collectList())
                .expectNextMatches(List::isEmpty)
                .verifyComplete();
    }
}
