package com.lvoxx.sssm.media.web.dto;

import com.lvoxx.sssm.media.domain.Media;
import com.lvoxx.sssm.media.domain.MediaStatus;
import com.lvoxx.sssm.media.service.ImgproxyUrlBuilder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a {@link Media} record. {@code variants} carries the signed imgproxy URLs for the
 * AVIF/WebP representations; it is empty while the media is still {@code PENDING} (nothing to serve
 * until the upload is confirmed). The R2 original is private and is never exposed directly.
 */
public record MediaResponse(
        UUID id,
        UUID ownerId,
        String contentType,
        Long sizeBytes,
        Integer width,
        Integer height,
        MediaStatus status,
        Instant createdAt,
        List<Variant> variants) {

    /** A single served representation: the target width, the format, and the signed imgproxy URL. */
    public record Variant(int width, String format, String url) {

        static Variant from(ImgproxyUrlBuilder.Variant v) {
            return new Variant(v.width(), v.format(), v.url());
        }
    }

    public static MediaResponse from(Media m, List<ImgproxyUrlBuilder.Variant> variants) {
        return new MediaResponse(
                m.getId(),
                m.getOwnerId(),
                m.getContentType(),
                m.getSizeBytes(),
                m.getWidth(),
                m.getHeight(),
                m.getStatus(),
                m.getCreatedAt(),
                variants.stream().map(Variant::from).toList());
    }
}
