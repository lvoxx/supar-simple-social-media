package io.github.lvoxx.media_service.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import io.github.lvoxx.media_service.entity.MediaAsset;
import reactor.core.publisher.Mono;

public interface MediaAssetRepository extends ReactiveCrudRepository<MediaAsset, UUID> {
    Mono<MediaAsset> findByIdAndIsDeletedFalse(UUID id);
}
