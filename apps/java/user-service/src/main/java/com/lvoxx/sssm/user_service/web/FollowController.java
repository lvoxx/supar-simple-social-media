package com.lvoxx.sssm.user_service.web;

import com.lvoxx.sssm.user_service.domain.Profile;
import com.lvoxx.sssm.user_service.security.AuthenticatedUser;
import com.lvoxx.sssm.user_service.service.CursorPage;
import com.lvoxx.sssm.user_service.service.FollowService;
import com.lvoxx.sssm.user_service.web.dto.PageResponse;
import com.lvoxx.sssm.user_service.web.dto.ProfileResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Follow graph endpoints nested under a target profile. {@code POST}/{@code DELETE} act as the
 * authenticated caller (gateway-forwarded identity); the follower/following listings are public
 * reads.
 */
@RestController
@RequestMapping("/api/v1/profiles/{username}")
public class FollowController {

    private final FollowService follows;

    public FollowController(FollowService follows) {
        this.follows = follows;
    }

    @PostMapping("/follow")
    @ResponseStatus(HttpStatus.CREATED)
    public void follow(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String username) {
        follows.follow(user.id(), username);
    }

    @DeleteMapping("/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String username) {
        follows.unfollow(user.id(), username);
    }

    @GetMapping("/followers")
    public PageResponse<ProfileResponse> followers(
            @PathVariable String username,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return toResponse(follows.followers(username, cursor, limit));
    }

    @GetMapping("/following")
    public PageResponse<ProfileResponse> following(
            @PathVariable String username,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return toResponse(follows.following(username, cursor, limit));
    }

    private static PageResponse<ProfileResponse> toResponse(CursorPage<Profile> page) {
        return new PageResponse<>(
                page.items().stream().map(ProfileResponse::from).toList(),
                page.nextCursor());
    }
}
