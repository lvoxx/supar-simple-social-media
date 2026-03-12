package io.github.lvoxx.user_service.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import io.github.lvoxx.user_service.entity.UserBlock;
import reactor.core.publisher.Mono;

public interface BlockRepository extends ReactiveCrudRepository<UserBlock, UUID> {

    Mono<Boolean> existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    Mono<Void> deleteByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);
}
