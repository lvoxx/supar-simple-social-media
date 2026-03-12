package io.github.lvoxx.media_service.dto;

import java.util.UUID;

public record MediaUploadResponse(UUID id, String status, String cdnUrl, String thumbnailUrl,
                String contentType, Long fileSizeBytes) {
}
