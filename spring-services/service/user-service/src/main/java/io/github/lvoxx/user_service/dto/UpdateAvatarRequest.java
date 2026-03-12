package io.github.lvoxx.user_service.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAvatarRequest(@NotBlank String mediaId) {}
