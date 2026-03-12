package io.github.lvoxx.user_service.dto;

import io.github.lvoxx.common_core.model.PageResponse;

public record PagedFollowerResponse(PageResponse<UserResponse> page) {
}
