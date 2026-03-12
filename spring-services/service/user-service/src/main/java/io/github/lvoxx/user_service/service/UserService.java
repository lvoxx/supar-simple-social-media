package io.github.lvoxx.user_service.service;

import java.util.UUID;

import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.common_core.security.UserPrincipal;
import io.github.lvoxx.user_service.dto.AccountHistoryResponse;
import io.github.lvoxx.user_service.dto.FollowRequestResponse;
import io.github.lvoxx.user_service.dto.UpdateProfileRequest;
import io.github.lvoxx.user_service.dto.UpdateSettingsRequest;
import io.github.lvoxx.user_service.dto.UserResponse;
import io.github.lvoxx.user_service.dto.VerificationRequest;
import reactor.core.publisher.Mono;

public interface UserService {

    // ── Profile ───────────────────────────────────────────────────────────────

    Mono<UserResponse> getByUsername(String username);

    Mono<UserResponse> getById(UUID userId);

    Mono<UserResponse> updateProfile(UserPrincipal principal, UpdateProfileRequest req);

    Mono<UserResponse> updateAvatar(UserPrincipal principal, String mediaId);

    Mono<UserResponse> updateBackground(UserPrincipal principal, String mediaId);

    Mono<UserResponse> updateSettings(UserPrincipal principal, UpdateSettingsRequest req);

    // ── Social graph ──────────────────────────────────────────────────────────

    Mono<Void> follow(UserPrincipal principal, UUID targetId);

    Mono<Void> unfollow(UserPrincipal principal, UUID targetId);

    Mono<PageResponse<UserResponse>> getFollowers(UUID userId, String cursor, int size);

    Mono<PageResponse<UserResponse>> getFollowing(UUID userId, String cursor, int size);

    // ── Follow requests ───────────────────────────────────────────────────────

    Mono<PageResponse<FollowRequestResponse>> getFollowRequests(UserPrincipal principal);

    Mono<Void> respondFollowRequest(UserPrincipal principal, UUID reqId, String action);

    // ── History & search ──────────────────────────────────────────────────────

    Mono<PageResponse<AccountHistoryResponse>> getHistory(UserPrincipal principal);

    Mono<PageResponse<UserResponse>> searchUsers(String query, int size);

    // ── Verification ──────────────────────────────────────────────────────────

    Mono<Void> submitVerification(UserPrincipal principal, VerificationRequest req);
}
