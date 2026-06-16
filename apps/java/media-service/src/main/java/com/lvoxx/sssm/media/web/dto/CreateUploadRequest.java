package com.lvoxx.sssm.media.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for requesting a presigned upload URL. The owner is taken from the gateway-forwarded
 * identity, never from this body. {@code contentType} must be a supported image type (validated in
 * the service); it is echoed into the presigned PUT so R2 stores the object with the right type.
 */
public record CreateUploadRequest(
        @NotBlank
        String contentType) {
}
