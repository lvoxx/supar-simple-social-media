package io.github.lvoxx.user_service.web.handler;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.github.lvoxx.common_core.model.ApiResponse;
import io.github.lvoxx.common_core.util.ReactiveContextUtil;
import io.github.lvoxx.user_service.dto.UpdateProfileRequest;
import io.github.lvoxx.user_service.dto.UpdateSettingsRequest;
import io.github.lvoxx.user_service.dto.VerificationRequest;
import io.github.lvoxx.user_service.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * WebFlux functional handler for all user-related HTTP endpoints.
 *
 * <p>
 * All endpoints require a valid JWT forwarded by the API Gateway as the
 * {@code X-User-Id} and {@code X-User-Roles} headers. The
 * {@code security-starter}
 * parses these into a
 * {@link io.github.lvoxx.common_core.security.UserPrincipal}
 * stored in the Reactor context.
 *
 * <p>
 * Cache TTLs:
 * <ul>
 * <li>{@code user:profile:{userId}} — 5 min</li>
 * <li>{@code user:profile:username:{username}} — 5 min</li>
 * <li>{@code user:followers:count:{userId}} — 1 min</li>
 * </ul>
 *
 * @see UserService
 */
@Component
@RequiredArgsConstructor
@Tag(name = "Users", description = "User profile, social graph, follow requests and account management")
@SecurityRequirement(name = "bearerAuth")
public class UserHandler {

    private final UserService userService;

    // ── Profile ───────────────────────────────────────────────────────────────

    /**
     * Returns the public profile of any user by their username.
     *
     * @param req the server request containing the {@code username} path variable
     * @return 200 with
     *         {@link io.github.lvoxx.user_service.application.dto.UserResponse},
     *         404 if the user does not exist
     */
    @Operation(summary = "Get public profile by username", description = "Returns public profile fields for any active user. Cached for 5 minutes.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    public Mono<ServerResponse> getByUsername(ServerRequest req) {
        String username = req.pathVariable("username");
        return userService.getByUsername(username)
                .flatMap(u -> ServerResponse.ok().bodyValue(ApiResponse.success(u)));
    }

    /**
     * Returns the full profile of the currently authenticated user.
     *
     * @param req the server request (principal resolved from Reactor context)
     * @return 200 with own
     *         {@link io.github.lvoxx.user_service.application.dto.UserResponse}
     */
    @Operation(summary = "Get own profile", description = "Returns all profile fields including private ones for the authenticated user.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Own profile")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated")
    public Mono<ServerResponse> getMe(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> userService.getById(p.userId()))
                .flatMap(u -> ServerResponse.ok().bodyValue(ApiResponse.success(u)));
    }

    /**
     * Updates mutable profile fields of the authenticated user.
     * Partial updates are supported — any null field in the request body is
     * ignored.
     * Publishes a {@code user.profile.updated} Avro event on success.
     *
     * @param req the server request; body must be a valid
     *            {@link UpdateProfileRequest}
     * @return 200 with updated
     *         {@link io.github.lvoxx.user_service.application.dto.UserResponse}
     */
    @Operation(summary = "Update own profile", description = "Partial update of displayName, bio, websiteUrl, location, birthDate, isPrivate. "
            + "Triggers user.profile.updated Avro event.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Validation error")
    public Mono<ServerResponse> updateProfile(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> req.bodyToMono(UpdateProfileRequest.class)
                        .flatMap(body -> userService.updateProfile(p, body)))
                .flatMap(u -> ServerResponse.ok().bodyValue(ApiResponse.success(u)));
    }

    /**
     * Replaces the user's avatar with a previously uploaded media asset.
     * Publishes a {@code user.avatar.changed} Avro event.
     *
     * @param req body: {@code {"mediaId": "<UUID>"}} — must reference a READY media
     *            asset owned by the caller
     * @return 200 with updated profile
     */
    @Operation(summary = "Update avatar", description = "Replaces the user's avatar. mediaId must be a READY media asset. "
            + "Triggers user.avatar.changed Avro event.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Avatar updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Media not found")
    public Mono<ServerResponse> updateAvatar(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> req.bodyToMono(Map.class)
                        .map(m -> (String) m.get("mediaId"))
                        .flatMap(mediaId -> userService.updateAvatar(p, mediaId)))
                .flatMap(u -> ServerResponse.ok().bodyValue(ApiResponse.success(u)));
    }

    /**
     * Replaces the user's background (banner) image.
     * Publishes a {@code user.background.changed} Avro event.
     *
     * @param req body: {@code {"mediaId": "<UUID>"}}
     * @return 200 with updated profile
     */
    @Operation(summary = "Update background image", description = "Replaces the user's profile banner. Triggers user.background.changed Avro event.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Background updated")
    public Mono<ServerResponse> updateBackground(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> req.bodyToMono(Map.class)
                        .map(m -> (String) m.get("mediaId"))
                        .flatMap(mediaId -> userService.updateBackground(p, mediaId)))
                .flatMap(u -> ServerResponse.ok().bodyValue(ApiResponse.success(u)));
    }

    /**
     * Updates account-level settings (notification preferences, theme, privacy
     * options).
     *
     * @param req body: JSON object of settings fields
     * @return 200 on success
     */
    @Operation(summary = "Update account settings", description = "Updates notification preferences, theme, and privacy settings.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Settings updated")
    public Mono<ServerResponse> updateSettings(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> req.bodyToMono(UpdateSettingsRequest.class)
                        .flatMap(body -> userService.updateSettings(p, body)))
                .flatMap(u -> ServerResponse.ok().bodyValue(ApiResponse.success(u)));
    }

    // ── Social graph ──────────────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated list of a user's followers.
     *
     * @param req path: {@code userId}; query: {@code cursor} (optional),
     *            {@code size} (default 20)
     * @return 200 with {@link io.github.lvoxx.common_core.model.PageResponse} of
     *         user summaries
     */
    @Operation(summary = "Get followers", description = "Cursor-paginated list of users following the specified user.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Followers page")
    public Mono<ServerResponse> getFollowers(ServerRequest req) {
        UUID userId = UUID.fromString(req.pathVariable("userId"));
        String cursor = req.queryParam("cursor").orElse(null);
        int size = Integer.parseInt(req.queryParam("size").orElse("20"));
        return userService.getFollowers(userId, cursor, size)
                .flatMap(page -> ServerResponse.ok().bodyValue(ApiResponse.success(page)));
    }

    /**
     * Returns a cursor-paginated list of users that a user is following.
     *
     * @param req path: {@code userId}; query: {@code cursor}, {@code size}
     * @return 200 with {@link io.github.lvoxx.common_core.model.PageResponse}
     */
    @Operation(summary = "Get following", description = "Cursor-paginated list of users that the specified user is following.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Following page")
    public Mono<ServerResponse> getFollowing(ServerRequest req) {
        UUID userId = UUID.fromString(req.pathVariable("userId"));
        String cursor = req.queryParam("cursor").orElse(null);
        int size = Integer.parseInt(req.queryParam("size").orElse("20"));
        return userService.getFollowing(userId, cursor, size)
                .flatMap(page -> ServerResponse.ok().bodyValue(ApiResponse.success(page)));
    }

    /**
     * Follows a user. For private accounts, creates a follow request instead.
     * Rate-limited to 50 calls/hour per user. Publishes {@code user.followed} Avro
     * event.
     *
     * @param req path: {@code userId} — target user to follow
     * @return 204 No Content on success, 409 if already following
     */
    @Operation(summary = "Follow a user", description = "Follows the specified user. For private accounts creates a PENDING request. "
            + "Rate limit: 50/hour. Triggers user.followed Avro event.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Followed successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already following")
    public Mono<ServerResponse> follow(ServerRequest req) {
        UUID targetId = UUID.fromString(req.pathVariable("userId"));
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> userService.follow(p, targetId))
                .then(ServerResponse.status(HttpStatus.NO_CONTENT).build());
    }

    /**
     * Unfollows a user. Publishes {@code user.unfollowed} Avro event.
     *
     * @param req path: {@code userId} — target user to unfollow
     * @return 204 No Content, 409 if not currently following
     */
    @Operation(summary = "Unfollow a user", description = "Removes the follow relationship. Triggers user.unfollowed Avro event.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Unfollowed")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Not following")
    public Mono<ServerResponse> unfollow(ServerRequest req) {
        UUID targetId = UUID.fromString(req.pathVariable("userId"));
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> userService.unfollow(p, targetId))
                .then(ServerResponse.status(HttpStatus.NO_CONTENT).build());
    }

    // ── Follow requests ───────────────────────────────────────────────────────

    /**
     * Returns pending follow requests received by the authenticated user.
     *
     * @param req server request
     * @return 200 with list of pending follow requests
     */
    @Operation(summary = "Get incoming follow requests", description = "Returns all PENDING follow requests targeting the authenticated user.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Follow request list")
    public Mono<ServerResponse> getFollowRequests(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> userService.getFollowRequests(p))
                .flatMap(page -> ServerResponse.ok().bodyValue(ApiResponse.success(page)));
    }

    /**
     * Approves or rejects a follow request.
     *
     * @param req path: {@code reqId}; body: {@code {"action": "APPROVE"|"REJECT"}}
     * @return 204 No Content
     */
    @Operation(summary = "Respond to follow request", description = "Approves or rejects a pending follow request. Action: APPROVE | REJECT")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Request actioned")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Request not found")
    public Mono<ServerResponse> respondFollowRequest(ServerRequest req) {
        UUID reqId = UUID.fromString(req.pathVariable("reqId"));
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> req.bodyToMono(Map.class)
                        .map(m -> (String) m.get("action"))
                        .flatMap(action -> userService.respondFollowRequest(p, reqId, action)))
                .then(ServerResponse.status(HttpStatus.NO_CONTENT).build());
    }

    // ── History & Verification ────────────────────────────────────────────────

    /**
     * Returns the account action history of the authenticated user (login events,
     * password changes, profile updates).
     *
     * @param req server request
     * @return 200 with list of
     *         {@link io.github.lvoxx.user_service.domain.entity.AccountHistory}
     *         entries
     */
    @Operation(summary = "Get account history", description = "Returns the last 100 account events (login, password change, profile update) for the caller.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Account history")
    public Mono<ServerResponse> getHistory(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> userService.getHistory(p))
                .flatMap(page -> ServerResponse.ok().bodyValue(ApiResponse.success(page)));
    }

    /**
     * Submits an identity verification request.
     * Rate-limited to 3 per day. Requires document upload via media-service first.
     *
     * @param req body:
     *            {@code {"documentMediaId": "<UUID>", "type": "IDENTITY|BUSINESS"}}
     * @return 202 Accepted (verification is reviewed asynchronously)
     */
    @Operation(summary = "Submit identity verification", description = "Submits a verification request for admin review. Rate limit: 3/day. "
            + "Requires a pre-uploaded document mediaId.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Verification submitted")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    public Mono<ServerResponse> submitVerification(ServerRequest req) {
        return ReactiveContextUtil.getCurrentUser()
                .flatMap(p -> req.bodyToMono(VerificationRequest.class)
                        .flatMap(body -> userService.submitVerification(p, body)))
                .then(ServerResponse.status(HttpStatus.ACCEPTED).build());
    }

    /**
     * Searches for users by username prefix or display name.
     *
     * @param req query param: {@code q} (minimum 2 chars)
     * @return 200 with matching user summaries
     */
    @Operation(summary = "Search users", description = "Prefix search on username and displayName. Minimum query length: 2 characters.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Search results")
    public Mono<ServerResponse> searchUsers(ServerRequest req) {
        String q = req.queryParam("q").orElse("");
        int size = Integer.parseInt(req.queryParam("size").orElse("20"));
        return userService.searchUsers(q, size)
                .flatMap(page -> ServerResponse.ok().bodyValue(ApiResponse.success(page)));
    }
}
