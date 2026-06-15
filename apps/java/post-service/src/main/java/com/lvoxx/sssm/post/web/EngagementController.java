package com.lvoxx.sssm.post.web;

import com.lvoxx.sssm.post.security.AuthenticatedUser;
import com.lvoxx.sssm.post.service.EngagementService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Engagement endpoints for a post: like, repost and bookmark. Each is an idempotent toggle — PUT
 * adds the engagement, DELETE removes it — so clients can safely retry. All require the
 * gateway-forwarded identity (the actor); none accept an actor in the body. Each returns 204.
 */
@RestController
@RequestMapping("/api/v1/posts/{id}")
public class EngagementController {

    private final EngagementService engagements;

    public EngagementController(EngagementService engagements) {
        this.engagements = engagements;
    }

    @PutMapping("/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void like(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        engagements.like(user.id(), id);
    }

    @DeleteMapping("/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlike(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        engagements.unlike(user.id(), id);
    }

    @PutMapping("/repost")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void repost(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        engagements.repost(user.id(), id);
    }

    @DeleteMapping("/repost")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unrepost(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        engagements.unrepost(user.id(), id);
    }

    @PutMapping("/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bookmark(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        engagements.bookmark(user.id(), id);
    }

    @DeleteMapping("/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbookmark(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        engagements.unbookmark(user.id(), id);
    }
}
