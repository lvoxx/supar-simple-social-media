package io.github.lvoxx.user_service.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.github.lvoxx.common_core.enums.UserRole;
import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.common_core.security.UserPrincipal;
import io.github.lvoxx.common_core.util.ReactiveContextUtil;
import io.github.lvoxx.user_service.dto.AccountHistoryResponse;
import io.github.lvoxx.user_service.dto.FollowRequestResponse;
import io.github.lvoxx.user_service.dto.UserResponse;
import io.github.lvoxx.user_service.service.UserService;
import io.github.lvoxx.user_service.web.handler.UserHandler;
import io.github.lvoxx.user_service.web.router.UserRouter;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link UserHandler} HTTP endpoints.
 *
 * <p>Uses {@link WebTestClient} bound directly to the {@link io.github.lvoxx.user_service.web.router.UserRouter}
 * function — no Spring Boot context is started. A {@link io.github.lvoxx.common_core.security.UserPrincipal}
 * for user {@code "alice"} is injected into the reactive subscriber context via a WebFilter,
 * simulating an authenticated request without any security infrastructure.
 *
 * <p>Each test stubs exactly the {@link UserService} method that the endpoint under test delegates to,
 * then asserts the HTTP status code and relevant JSON fields in the response body.
 */
@Tags({
        @Tag("UT"), @Tag("Mock"), @Tag("Handler")
})
@DisplayName("UserHandler — HTTP endpoints via WebTestClient")
@ExtendWith(MockitoExtension.class)
class UserHandlerTest {

    @Mock
    private UserService userService;

    private WebTestClient client;
    private UserPrincipal testPrincipal;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testPrincipal = new UserPrincipal(userId, "alice", Set.of(UserRole.USER), "127.0.0.1");

        UserHandler handler = new UserHandler(userService);
        client = WebTestClient
                .bindToRouterFunction(new UserRouter().userRoutes(handler))
                .webFilter((exchange, chain) -> chain.filter(exchange)
                        .contextWrite(ctx -> ctx.put(
                                ReactiveContextUtil.PRINCIPAL_CONTEXT_KEY, testPrincipal)))
                .build();
    }

    private UserResponse buildUserResponse() {
        return new UserResponse(
                userId, "alice", "Alice", "Bio", null, null, null,
                null, false, false, 0, 0, 0, "USER", Instant.now());
    }

    // ── GET /api/v1/users/{username} ──────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/users/{username}: given existing user → returns 200 with user data")
    void getByUsername_givenExistingUser_returns200() {
        when(userService.getByUsername("alice")).thenReturn(Mono.just(buildUserResponse()));

        client.get().uri("/api/v1/users/alice")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.username").isEqualTo("alice");
    }

    @Test
    @DisplayName("GET /api/v1/users/{username}: given non-existing user → propagates 404")
    void getByUsername_givenNonExistingUser_propagates404() {
        when(userService.getByUsername("ghost"))
                .thenReturn(Mono.error(new ResourceNotFoundException("USER_NOT_FOUND", "ghost")));

        client.get().uri("/api/v1/users/ghost")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── GET /api/v1/users/me ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/users/me: given principal in context → returns 200 with own profile")
    void getMe_givenPrincipalInContext_returns200() {
        when(userService.getById(userId)).thenReturn(Mono.just(buildUserResponse()));

        client.get().uri("/api/v1/users/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.username").isEqualTo("alice");
    }

    // ── PUT /api/v1/users/me ──────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /api/v1/users/me: given valid body → returns 200 with updated profile")
    void updateProfile_givenValidBody_returns200() {
        when(userService.updateProfile(eq(testPrincipal), any()))
                .thenReturn(Mono.just(buildUserResponse()));

        client.put().uri("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"displayName":"New Name"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true);
    }

    // ── PUT /api/v1/users/me/avatar ───────────────────────────────────────────

    @Test
    @DisplayName("PUT /api/v1/users/me/avatar: given valid mediaId → returns 200")
    void updateAvatar_givenValidMediaId_returns200() {
        when(userService.updateAvatar(eq(testPrincipal), eq("media-id-123")))
                .thenReturn(Mono.just(buildUserResponse()));

        client.put().uri("/api/v1/users/me/avatar")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"mediaId":"media-id-123"}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    // ── PUT /api/v1/users/me/background ──────────────────────────────────────

    @Test
    @DisplayName("PUT /api/v1/users/me/background: given valid mediaId → returns 200")
    void updateBackground_givenValidMediaId_returns200() {
        when(userService.updateBackground(eq(testPrincipal), eq("bg-media-456")))
                .thenReturn(Mono.just(buildUserResponse()));

        client.put().uri("/api/v1/users/me/background")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"mediaId":"bg-media-456"}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    // ── PUT /api/v1/users/me/settings ─────────────────────────────────────────

    @Test
    @DisplayName("PUT /api/v1/users/me/settings: given valid body → returns 200")
    void updateSettings_givenValidBody_returns200() {
        when(userService.updateSettings(eq(testPrincipal), any()))
                .thenReturn(Mono.just(buildUserResponse()));

        client.put().uri("/api/v1/users/me/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"themeSettings":{"mode":"dark"}}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    // ── GET /api/v1/users/{userId}/followers ──────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/users/{userId}/followers: given userId → returns 200 with followers page")
    void getFollowers_givenUserId_returns200() {
        PageResponse<UserResponse> page = PageResponse.of(List.of(buildUserResponse()), null);
        when(userService.getFollowers(any(UUID.class), isNull(), eq(20)))
                .thenReturn(Mono.just(page));

        client.get().uri("/api/v1/users/{userId}/followers", userId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items").isArray();
    }

    // ── GET /api/v1/users/{userId}/following ──────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/users/{userId}/following: given userId → returns 200 with following page")
    void getFollowing_givenUserId_returns200() {
        PageResponse<UserResponse> page = PageResponse.empty();
        when(userService.getFollowing(any(UUID.class), isNull(), eq(20)))
                .thenReturn(Mono.just(page));

        client.get().uri("/api/v1/users/{userId}/following", userId)
                .exchange()
                .expectStatus().isOk();
    }

    // ── POST /api/v1/users/{userId}/follow ────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/users/{userId}/follow: given valid target → returns 204")
    void follow_givenValidTarget_returns204() {
        UUID targetId = UUID.randomUUID();
        when(userService.follow(eq(testPrincipal), eq(targetId))).thenReturn(Mono.empty());

        client.post().uri("/api/v1/users/{userId}/follow", targetId)
                .exchange()
                .expectStatus().isNoContent();
    }

    // ── DELETE /api/v1/users/{userId}/follow ──────────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/users/{userId}/follow: given valid target → returns 204")
    void unfollow_givenValidTarget_returns204() {
        UUID targetId = UUID.randomUUID();
        when(userService.unfollow(eq(testPrincipal), eq(targetId))).thenReturn(Mono.empty());

        client.delete().uri("/api/v1/users/{userId}/follow", targetId)
                .exchange()
                .expectStatus().isNoContent();
    }

    // ── GET /api/v1/users/me/follow-requests ─────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/users/me/follow-requests: given principal → returns 200 with pending requests")
    void getFollowRequests_givenPrincipal_returns200() {
        PageResponse<FollowRequestResponse> page = PageResponse.empty();
        when(userService.getFollowRequests(testPrincipal)).thenReturn(Mono.just(page));

        client.get().uri("/api/v1/users/me/follow-requests")
                .exchange()
                .expectStatus().isOk();
    }

    // ── PUT /api/v1/users/me/follow-requests/{reqId} ──────────────────────────

    @Test
    @DisplayName("PUT /api/v1/users/me/follow-requests/{reqId}: given APPROVE action → returns 204")
    void respondFollowRequest_givenApproveAction_returns204() {
        UUID reqId = UUID.randomUUID();
        when(userService.respondFollowRequest(eq(testPrincipal), eq(reqId), eq("APPROVE")))
                .thenReturn(Mono.empty());

        client.put().uri("/api/v1/users/me/follow-requests/{reqId}", reqId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"action":"APPROVE"}
                        """)
                .exchange()
                .expectStatus().isNoContent();
    }

    // ── GET /api/v1/users/me/history ──────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/users/me/history: given principal → returns 200 with account history")
    void getHistory_givenPrincipal_returns200() {
        PageResponse<AccountHistoryResponse> page = PageResponse.empty();
        when(userService.getHistory(testPrincipal)).thenReturn(Mono.just(page));

        client.get().uri("/api/v1/users/me/history")
                .exchange()
                .expectStatus().isOk();
    }

    // ── POST /api/v1/users/me/verify ──────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/users/me/verify: given valid document request → returns 202")
    void submitVerification_givenValidRequest_returns202() {
        when(userService.submitVerification(eq(testPrincipal), any())).thenReturn(Mono.empty());

        client.post().uri("/api/v1/users/me/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"documentMediaId":"doc-123","type":"IDENTITY"}
                        """)
                .exchange()
                .expectStatus().isAccepted();
    }

    // ── GET /api/v1/users/search ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/users/search: given query param → returns 200 with matching users")
    void searchUsers_givenQuery_returns200() {
        PageResponse<UserResponse> page = PageResponse.of(List.of(buildUserResponse()), null);
        when(userService.searchUsers("alice", 10)).thenReturn(Mono.just(page));

        client.get().uri(uriBuilder -> uriBuilder
                        .path("/api/v1/users/search")
                        .queryParam("q", "alice")
                        .queryParam("size", "10")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items").isArray();
    }
}
