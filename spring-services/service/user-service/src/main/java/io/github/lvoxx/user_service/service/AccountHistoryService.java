package io.github.lvoxx.user_service.service;

import java.util.UUID;

import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.user_service.dto.AccountHistoryResponse;
import reactor.core.publisher.Mono;

public interface AccountHistoryService {

    /**
     * Appends a new account-history entry (e.g. login, password change, profile update).
     * Fire-and-forget: callers may subscribe on a separate scheduler and ignore errors.
     */
    Mono<Void> record(UUID userId, String action, String detail, String ip);

    /**
     * Returns the most recent {@code limit} history entries for a user, newest first.
     * 404 if the user has no history (returns an empty page, never an error).
     */
    Mono<PageResponse<AccountHistoryResponse>> getHistory(UUID userId, int limit);
}
