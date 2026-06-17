package com.lvoxx.sssm.post_service.domain;

/**
 * The kind of engagement a user can have with a post. Stored as the string {@code type} column of
 * {@code sssm.post_engagements} and part of that table's primary key, so a user holds at most one of
 * each kind per post. The add-vs-remove distinction (like vs unlike) is not stored here; it is
 * carried only by the emitted {@code sssm.event.v1.PostEngagement} event.
 */
public enum EngagementKind {
    LIKE,
    REPOST,
    BOOKMARK
}
