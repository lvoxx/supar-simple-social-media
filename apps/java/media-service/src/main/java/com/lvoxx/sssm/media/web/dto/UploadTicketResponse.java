package com.lvoxx.sssm.media.web.dto;

import com.lvoxx.sssm.media.service.MediaService.UploadTicket;
import java.time.Instant;
import java.util.UUID;

/**
 * Response to an upload request: the new media id, the presigned URL to {@code PUT} the original to
 * (directly to R2), the R2 object key, and when the URL expires. After uploading, the client calls
 * {@code POST /api/v1/media/{mediaId}/complete} to finalize.
 */
public record UploadTicketResponse(
        UUID mediaId,
        String uploadUrl,
        String objectKey,
        Instant expiresAt) {

    public static UploadTicketResponse from(UploadTicket ticket) {
        return new UploadTicketResponse(
                ticket.mediaId(), ticket.uploadUrl(), ticket.objectKey(), ticket.expiresAt());
    }
}
