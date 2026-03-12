package io.github.lvoxx.user_service.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import io.github.lvoxx.user_service.entity.UserSettings;
import reactor.core.publisher.Mono;

public interface UserSettingsRepository extends ReactiveCrudRepository<UserSettings, UUID> {

    Mono<UserSettings> findByUserId(UUID userId);
}
