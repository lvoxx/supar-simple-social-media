package com.lvoxx.sssm.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An uploaded image. Maps to the infrastructure-owned {@code sssm.media} table (see
 * {@code deploy/migrations/media-service/V1__baseline.sql}). The app runs with
 * {@code ddl-auto=validate} and never creates or alters this table, so every column mapped here must
 * match the baseline migration exactly.
 *
 * <p>Only the original object lives in R2 (addressed by {@code objectKey}); AVIF/WebP variants are
 * transcoded on demand by imgproxy, so no derived bytes are tracked. A row starts {@code PENDING}
 * when the upload URL is issued and is promoted to {@code READY} once the client confirms the
 * upload (the service learns the real {@code sizeBytes}/{@code contentType} from a HEAD). The
 * mutable lifecycle fields stay package-mutable via explicit methods rather than Lombok setters
 * (per the entity guardrails in CONTRIBUTING.md: only {@code @Getter} on entities).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "media")
public class Media {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MediaStatus status = MediaStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Media(UUID ownerId, String objectKey, String contentType) {
        this.ownerId = ownerId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.status = MediaStatus.PENDING;
    }

    /**
     * Promotes the row to {@code READY} once the direct-to-R2 upload has been confirmed. The real
     * size and content type come from the R2 HEAD; {@code width}/{@code height} are optional layout
     * hints the client may supply (the service does not decode the image).
     */
    public void markReady(String contentType, Long sizeBytes, Integer width, Integer height) {
        this.status = MediaStatus.READY;
        if (contentType != null && !contentType.isBlank()) {
            this.contentType = contentType;
        }
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
    }

    public boolean isReady() {
        return status == MediaStatus.READY;
    }
}
