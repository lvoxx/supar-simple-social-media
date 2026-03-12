package io.github.lvoxx.media_service.service.impl;

import java.util.UUID;

import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;

import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_core.message.MessageKeys;
import io.github.lvoxx.common_core.util.UlidGenerator;
import io.github.lvoxx.media_service.dto.MediaUploadResponse;
import io.github.lvoxx.media_service.entity.MediaAsset;
import io.github.lvoxx.media_service.kafka.MediaEventPublisher;
import io.github.lvoxx.media_service.properties.S3Properties;
import io.github.lvoxx.media_service.repository.MediaAssetRepository;
import io.github.lvoxx.media_service.service.MediaService;
import io.github.lvoxx.media_service.service.StorageService;
import io.github.lvoxx.tika_starter.service.TikaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {

    private final MediaAssetRepository repo;
    private final TikaService tikaService;
    private final StorageService storageService;
    private final S3Properties s3Properties;
    private final MediaEventPublisher eventPublisher;

    @Override
    public Mono<MediaUploadResponse> upload(UUID ownerId, String ownerType, FilePart file) {
        UUID assetId = UlidGenerator.generateAsUUID();
        String s3Key = s3Properties.getDefaultPrefix()
                + "/" + ownerType.toLowerCase()
                + "/" + assetId;

        MediaAsset asset = MediaAsset.builder()
                .id(assetId)
                .ownerId(ownerId)
                .ownerType(ownerType)
                .originalFilename(file.filename())
                .contentType(tikaService.detect(file))
                .s3Key(s3Key)
                .s3Bucket(s3Properties.getBucket())
                .status("PROCESSING")
                .build();

        return repo.save(asset)
                .flatMap(saved -> {
                    Mono.fromRunnable(() -> processUpload(saved, file, s3Key))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
                    return Mono.just(toResponse(saved));
                });
    }

    @Override
    public Mono<MediaUploadResponse> getById(UUID mediaId) {
        return repo.findByIdAndIsDeletedFalse(mediaId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(MessageKeys.MEDIA_NOT_FOUND, mediaId)))
                .map(this::toResponse);
    }

    @Override
    public Mono<Void> softDelete(UUID ownerId, UUID mediaId) {
        return repo.findByIdAndIsDeletedFalse(mediaId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(MessageKeys.MEDIA_NOT_FOUND, mediaId)))
                .flatMap(asset -> {
                    asset.softDelete(ownerId);
                    asset.setStatus("DELETED");
                    // Fire-and-forget S3 deletion
                    storageService.delete(asset.getS3Key())
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe(
                                    null,
                                    e -> log.warn("S3 delete failed for key {}: {}", asset.getS3Key(), e.getMessage()));
                    return repo.save(asset);
                }).then();
    }

    private Mono<MediaUploadResponse> processUpload(MediaAsset asset, FilePart file, String s3Key) {
        return storageService.upload(file, s3Key)
                .flatMap(cdnUrl -> {
                    asset.setCdnUrl(cdnUrl);
                    asset.setStatus("READY");
                    return repo.save(asset);
                })
                .flatMap(saved -> eventPublisher.publishUploadCompleted(asset).thenReturn(saved))
                .map(this::toResponse)
                .onErrorResume(ex -> {
                    log.error("Upload pipeline failed for asset {}: {}", asset.getId(), ex.getMessage(), ex);
                    asset.setStatus("FAILED");
                    return repo.save(asset).map(this::toResponse);
                });
    }

    private MediaUploadResponse toResponse(MediaAsset a) {
        return new MediaUploadResponse(a.getId(), a.getStatus(), a.getCdnUrl(),
                a.getThumbnailUrl(), a.getContentType(), a.getFileSizeBytes());
    }
}
