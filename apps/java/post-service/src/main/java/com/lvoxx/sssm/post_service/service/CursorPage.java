package com.lvoxx.sssm.post_service.service;

import java.util.List;

/**
 * Service-layer page of results plus the cursor to fetch the next page ({@code null} when the page
 * is the last one). Kept separate from the web DTO so the service layer has no dependency on the
 * HTTP representation.
 *
 * @param <T> item type
 */
public record CursorPage<T>(List<T> items, String nextCursor) {
}
