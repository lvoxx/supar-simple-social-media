package io.github.lvoxx.common_core.model;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore,
        Long total) {
    public static <T> PageResponse<T> of(List<T> items, String nextCursor) {
        return new PageResponse<>(items, nextCursor, nextCursor != null, null);
    }

    public static <T> PageResponse<T> of(List<T> items, String nextCursor, long total) {
        return new PageResponse<>(items, nextCursor, nextCursor != null, total);
    }

    public static <T> PageResponse<T> empty() {
        return new PageResponse<>(List.of(), null, false, 0L);
    }
}
