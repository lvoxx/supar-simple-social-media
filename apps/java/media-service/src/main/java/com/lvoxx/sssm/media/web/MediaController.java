package com.lvoxx.sssm.media.web;

import com.lvoxx.sssm.media.domain.Media;
import com.lvoxx.sssm.media.security.AuthenticatedUser;
import com.lvoxx.sssm.media.service.MediaService;
import com.lvoxx.sssm.media.web.dto.CompleteUploadRequest;
import com.lvoxx.sssm.media.web.dto.CreateUploadRequest;
import com.lvoxx.sssm.media.web.dto.MediaResponse;
import com.lvoxx.sssm.media.web.dto.UploadTicketResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Image media endpoints. Reading a media record (and resolving its variant URLs) is public;
 * requesting an upload URL, confirming it, and deleting media act as the authenticated caller
 * (gateway-forwarded identity).
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    /** Requests a presigned URL to upload an image's original bytes directly to R2. */
    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadTicketResponse requestUpload(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateUploadRequest req) {
        return UploadTicketResponse.from(media.createUploadTicket(user.id(), req.contentType()));
    }

    /** Confirms the direct upload completed; promotes the media to READY and returns its view. */
    @PostMapping("/{id}/complete")
    public MediaResponse complete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CompleteUploadRequest req) {
        Integer width = req == null ? null : req.width();
        Integer height = req == null ? null : req.height();
        Media updated = media.completeUpload(user.id(), id, width, height);
        return MediaResponse.from(updated, media.variantsOf(updated));
    }

    @GetMapping("/{id}")
    public MediaResponse byId(@PathVariable UUID id) {
        Media m = media.getById(id);
        return MediaResponse.from(m, media.variantsOf(m));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        media.delete(user.id(), id);
    }
}
