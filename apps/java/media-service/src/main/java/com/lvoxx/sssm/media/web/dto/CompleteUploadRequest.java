package com.lvoxx.sssm.media.web.dto;

import jakarta.validation.constraints.Positive;

/**
 * Body for confirming an upload. Both fields are optional layout hints the client already knows
 * (the service does not decode the image); the real size/content-type come from the R2 HEAD. An
 * empty body is valid.
 */
public record CompleteUploadRequest(
        @Positive
        Integer width,

        @Positive
        Integer height) {
}
