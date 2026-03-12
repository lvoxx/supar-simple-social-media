package io.github.lvoxx.user_service.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import io.github.lvoxx.user_service.entity.FollowRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FollowRequestRepository extends ReactiveCrudRepository<FollowRequest, UUID> {

    Flux<FollowRequest> findByTargetIdAndStatus(UUID targetId, String status);

    Mono<FollowRequest> findByRequesterIdAndTargetId(UUID requesterId, UUID targetId);

    Mono<Boolean> existsByRequesterIdAndTargetIdAndStatus(UUID requesterId, UUID targetId, String status);
}
