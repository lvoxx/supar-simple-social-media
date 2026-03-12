package io.github.lvoxx.user_service.service.impl;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.common_core.util.UlidGenerator;
import io.github.lvoxx.user_service.dto.AccountHistoryResponse;
import io.github.lvoxx.user_service.entity.AccountHistory;
import io.github.lvoxx.user_service.repository.AccountHistoryRepository;
import io.github.lvoxx.user_service.service.AccountHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountHistoryServiceImpl implements AccountHistoryService {

    private final AccountHistoryRepository historyRepo;

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    public Mono<Void> record(UUID userId, String action, String detail, String ip) {
        return historyRepo.save(AccountHistory.builder()
                        .id(UlidGenerator.generateAsUUID())
                        .userId(userId)
                        .action(action)
                        .detail(detail)
                        .ip(ip)
                        .createdAt(Instant.now())
                        .build())
                .then()
                .doOnSuccess(v -> log.debug("Recorded history: userId={} action={}", userId, action))
                .doOnError(e -> log.error("Failed to record history: userId={} action={}", userId, action, e));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * History is append-only and updated on every {@link #record} call, so results
     * are intentionally not cached — staleness would be confusing for audit views.
     */
    @Override
    public Mono<PageResponse<AccountHistoryResponse>> getHistory(UUID userId, int limit) {
        return historyRepo.findByUserIdOrderByCreatedAtDesc(userId, limit)
                .map(h -> new AccountHistoryResponse(
                        h.getId(), h.getAction(), h.getDetail(), h.getIp(), h.getCreatedAt()))
                .collectList()
                .map(items -> PageResponse.of(items, null));
    }
}
