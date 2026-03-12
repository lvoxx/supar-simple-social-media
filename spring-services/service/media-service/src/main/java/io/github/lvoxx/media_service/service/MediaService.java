package io.github.lvoxx.media_service.service;

import java.util.UUID;

import org.springframework.http.codec.multipart.FilePart;

import io.github.lvoxx.media_service.dto.MediaUploadResponse;
import reactor.core.publisher.Mono;

public interface MediaService {
    Mono<MediaUploadResponse> upload(UUID ownerId, String ownerType, FilePart file);

    Mono<MediaUploadResponse> getById(UUID mediaId);

    Mono<Void> softDelete(UUID ownerId, UUID mediaId);
}
