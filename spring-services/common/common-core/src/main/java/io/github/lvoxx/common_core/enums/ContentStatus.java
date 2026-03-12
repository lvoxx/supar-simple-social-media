package io.github.lvoxx.common_core.enums;

public enum ContentStatus {
    ACTIVE,
    HIDDEN,         // ẩn bởi moderator
    FLAGGED,        // chờ review
    PENDING_REVIEW, // đang review
    DELETED         // soft deleted
}
