package io.github.lvoxx.user_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerificationRequest(
        @NotBlank String documentMediaId,
        @NotBlank @Pattern(regexp = "IDENTITY|BUSINESS") String type) {
}
