package io.github.lvoxx.user_service.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import io.github.lvoxx.user_service.entity.AccountHistory;
import reactor.core.publisher.Flux;

public interface AccountHistoryRepository extends ReactiveCrudRepository<AccountHistory, UUID> {

    @Query("SELECT * FROM account_history WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit")
    Flux<AccountHistory> findByUserIdOrderByCreatedAtDesc(UUID userId, int limit);
}
