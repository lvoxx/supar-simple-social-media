package io.github.lvoxx.user_service.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import io.github.lvoxx.user_service.entity.Verification;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface VerificationRepository extends ReactiveCrudRepository<Verification, UUID> {

    Flux<Verification> findByUserId(UUID userId);

    Mono<Boolean> existsByUserIdAndStatus(UUID userId, String status);
}
