package com.lvoxx.sssm.media.service;

import com.lvoxx.sssm.media.domain.Media;
import com.lvoxx.sssm.media.error.BadRequestException;
import com.lvoxx.sssm.media.error.ForbiddenException;
import com.lvoxx.sssm.media.error.NotFoundException;
import com.lvoxx.sssm.media.repository.MediaRepository;
import com.lvoxx.sssm.media.service.StorageService.HeadResult;
import com.lvoxx.sssm.media.service.StorageService.PresignedUpload;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Image media lifecycle: issue a presigned upload ticket, confirm the direct-to-R2 upload, read a
 * media record (with its on-demand imgproxy variant URLs), and delete one's own media.
 *
 * <p>The owner is always the gateway-forwarded identity ({@code ownerId}), never a request body
 * field. Only image content types are accepted, and the R2 object key is derived server-side so a
 * client can never dictate where bytes land.
 */
@Service
public class MediaService {

    /** Accepted upload content types → the file extension used in the R2 object key. */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/avif", "avif",
            "image/gif", "gif");

    private final MediaRepository repo;
    private final StorageService storage;
    private final ImgproxyUrlBuilder imgproxy;

    public MediaService(MediaRepository repo, StorageService storage, ImgproxyUrlBuilder imgproxy) {
        this.repo = repo;
        this.storage = storage;
        this.imgproxy = imgproxy;
    }

    /**
     * Reserves a PENDING media row and mints a presigned PUT URL the client uploads the original to,
     * directly to R2. The object key is {@code media/<owner>/<random>.<ext>} so it is unique and not
     * client-controlled.
     */
    @Transactional
    public UploadTicket createUploadTicket(UUID ownerId, String contentType) {
        String normalized = normalize(contentType);
        String ext = ALLOWED_TYPES.get(normalized);
        if (ext == null) {
            throw new BadRequestException("Unsupported image content type: " + contentType);
        }
        String objectKey = "media/" + ownerId + "/" + UUID.randomUUID() + "." + ext;
        Media saved = repo.saveAndFlush(new Media(ownerId, objectKey, normalized));
        PresignedUpload presigned = storage.presignUpload(objectKey, normalized);
        return new UploadTicket(saved.getId(), presigned.url(), objectKey, presigned.expiresAt());
    }

    /**
     * Confirms an upload: HEAD-verifies the object exists in R2 (404 if the client never uploaded),
     * records its real size/content-type, and promotes the row to READY. {@code width}/{@code height}
     * are optional client-supplied layout hints.
     */
    @Transactional
    public Media completeUpload(UUID ownerId, UUID mediaId, Integer width, Integer height) {
        Media media = getOwned(ownerId, mediaId);
        HeadResult head = storage.head(media.getObjectKey());
        media.markReady(head.contentType(), head.contentLength(), width, height);
        return media;
    }

    @Transactional(readOnly = true)
    public Media getById(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NotFoundException("No media with id " + id));
    }

    /** Deletes the caller's own media, removing both the DB row and the R2 original. */
    @Transactional
    public void delete(UUID ownerId, UUID mediaId) {
        Media media = getOwned(ownerId, mediaId);
        repo.delete(media);
        storage.delete(media.getObjectKey());
    }

    /**
     * The image's served representations (signed imgproxy URLs). Empty until the media is READY —
     * there is no original to transcode before the upload is confirmed.
     */
    public List<ImgproxyUrlBuilder.Variant> variantsOf(Media media) {
        return media.isReady() ? imgproxy.variantsFor(media.getObjectKey()) : List.of();
    }

    private Media getOwned(UUID ownerId, UUID mediaId) {
        Media media = getById(mediaId);
        if (!media.getOwnerId().equals(ownerId)) {
            throw new ForbiddenException("You can only modify your own media");
        }
        return media;
    }

    private static String normalize(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BadRequestException("contentType is required");
        }
        // Strip any parameters (e.g. "image/jpeg; charset=...") and lowercase for matching.
        int semicolon = contentType.indexOf(';');
        String base = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType);
        return base.trim().toLowerCase();
    }

    /** Result of reserving an upload: the new media id and where/how long to upload. */
    public record UploadTicket(UUID mediaId, String uploadUrl, String objectKey, Instant expiresAt) {
    }
}
