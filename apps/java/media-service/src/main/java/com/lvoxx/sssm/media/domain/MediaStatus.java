package com.lvoxx.sssm.media.domain;

/**
 * Lifecycle of a {@link Media} row.
 *
 * <ul>
 *   <li>{@link #PENDING} — a presigned upload URL was issued; the client has not yet confirmed the
 *       direct-to-R2 upload, so the object may not exist.</li>
 *   <li>{@link #READY} — the upload was confirmed (the object was HEAD-verified in R2) and the
 *       image can be served via its signed imgproxy variant URLs.</li>
 * </ul>
 */
public enum MediaStatus {
    PENDING,
    READY
}
