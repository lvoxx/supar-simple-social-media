package com.lvoxx.sssm.user_service.web.dto;

import java.util.List;

/**
 * A single page of cursor-paginated results. {@code nextCursor} is an opaque token to pass back as
 * {@code ?cursor=...} for the next page, or {@code null} when there are no more results.
 *
 * @param <T> item type
 */
public record PageResponse<T>(List<T> items, String nextCursor) {
}
