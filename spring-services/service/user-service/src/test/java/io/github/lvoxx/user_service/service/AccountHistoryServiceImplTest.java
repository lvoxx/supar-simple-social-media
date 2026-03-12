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

import io.github.lvoxx.user_service.entity.AccountHistory;
import io.github.lvoxx.user_service.repository.AccountHistoryRepository;
import io.github.lvoxx.user_service.service.impl.AccountHistoryServiceImpl;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("AccountHistoryService — record and retrieve account history")
@ExtendWith(MockitoExtension.class)
class AccountHistoryServiceImplTest {

    @Mock
    private AccountHistoryRepository historyRepo;

    private AccountHistoryServiceImpl accountHistoryService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        accountHistoryService = new AccountHistoryServiceImpl(historyRepo);
        userId = UUID.randomUUID();
    }

    // ── record ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("record: given valid input → saves AccountHistory with all provided fields")
    void record_givenValidInput_savesAccountHistory() {
        AccountHistory saved = AccountHistory.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .action("LOGIN")
                .detail("Browser login")
                .ip("192.168.1.1")
                .createdAt(Instant.now())
                .build();

        when(historyRepo.save(any(AccountHistory.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(accountHistoryService.record(userId, "LOGIN", "Browser login", "192.168.1.1"))
                .verifyComplete();

        verify(historyRepo).save(argThat(h ->
                userId.equals(h.getUserId())
                        && "LOGIN".equals(h.getAction())
                        && "Browser login".equals(h.getDetail())
                        && "192.168.1.1".equals(h.getIp())));
    }

    @Test
    @DisplayName("record: given null detail → saves AccountHistory with null detail field")
    void record_givenNullDetail_savesWithNullDetail() {
        AccountHistory saved = AccountHistory.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .action("PASSWORD_CHANGE")
                .detail(null)
                .ip("10.0.0.1")
                .createdAt(Instant.now())
                .build();

        when(historyRepo.save(any(AccountHistory.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(accountHistoryService.record(userId, "PASSWORD_CHANGE", null, "10.0.0.1"))
                .verifyComplete();

        verify(historyRepo).save(argThat(h -> h.getDetail() == null));
    }

    // ── getHistory ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHistory: given userId → returns PageResponse with actions in order")
    void getHistory_givenUserId_returnsMappedPageResponse() {
        AccountHistory h1 = AccountHistory.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .action("LOGIN")
                .detail("Mobile login")
                .ip("10.0.0.2")
                .createdAt(Instant.now())
                .build();

        AccountHistory h2 = AccountHistory.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .action("PROFILE_UPDATE")
                .detail("Updated bio")
                .ip("10.0.0.2")
                .createdAt(Instant.now().minusSeconds(60))
                .build();

        when(historyRepo.findByUserIdOrderByCreatedAtDesc(userId, 50))
                .thenReturn(Flux.just(h1, h2));

        StepVerifier.create(accountHistoryService.getHistory(userId, 50))
                .expectNextMatches(page ->
                        page.items().size() == 2
                                && "LOGIN".equals(page.items().get(0).action())
                                && "PROFILE_UPDATE".equals(page.items().get(1).action()))
                .verifyComplete();
    }

    @Test
    @DisplayName("getHistory: given no history records → returns empty PageResponse")
    void getHistory_givenNoHistory_returnsEmptyPage() {
        when(historyRepo.findByUserIdOrderByCreatedAtDesc(userId, 50))
                .thenReturn(Flux.empty());

        StepVerifier.create(accountHistoryService.getHistory(userId, 50))
                .expectNextMatches(page -> page.items().isEmpty())
                .verifyComplete();
    }
}
