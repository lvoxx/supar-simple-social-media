package io.github.lvoxx.media_service.entity;

import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import io.github.lvoxx.common_core.model.SoftDeletableEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("media_assets")
public class MediaAsset extends SoftDeletableEntity {
    @Id
    private UUID id;
    private UUID ownerId;
    @Builder.Default
    private String ownerType = "annonymous";
    private String originalFilename;
    private String contentType;
    private Long fileSizeBytes;
    private String s3Key;
    private String s3Bucket;
    private String cdnUrl;
    private String thumbnailUrl;
    private Integer width;
    private Integer height;
    private Integer durationSeconds;
    @Builder.Default
    private String status = "PROCESSING"; // PROCESSING|READY|REJECTED|DELETED
    private String rejectionReason;

    public boolean softDelete(UUID userId) {
        return super.softDelete(userId);
    }
}
