package io.github.lvoxx.user_service.service.impl;

import java.time.Instant;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.lvoxx.common_core.exception.ConflictException;
import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_core.exception.ValidationException;
import io.github.lvoxx.common_core.message.MessageKeys;
import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.common_core.security.UserPrincipal;
import io.github.lvoxx.common_core.util.UlidGenerator;
import io.github.lvoxx.common_keys.CacheKeys;
import io.github.lvoxx.user_service.dto.AccountHistoryResponse;
import io.github.lvoxx.user_service.dto.FollowRequestResponse;
import io.github.lvoxx.user_service.dto.UpdateProfileRequest;
import io.github.lvoxx.user_service.dto.UpdateSettingsRequest;
import io.github.lvoxx.user_service.dto.UserResponse;
import io.github.lvoxx.user_service.dto.VerificationRequest;
import io.github.lvoxx.user_service.entity.Verification;
import io.github.lvoxx.user_service.kafka.UserEventPublisher;
import io.github.lvoxx.user_service.repository.UserRepository;
import io.github.lvoxx.user_service.repository.VerificationRepository;
import io.github.lvoxx.user_service.service.AccountHistoryService;
import io.github.lvoxx.user_service.service.FollowRequestService;
import io.github.lvoxx.user_service.service.FollowerService;
import io.github.lvoxx.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    // ── Core repositories / publishers ────────────────────────────────────────
    private final UserRepository userRepo;
    private final VerificationRepository verificationRepo;
    private final UserEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    // ── Delegating services ───────────────────────────────────────────────────
    private final FollowerService followerService;
    private final FollowRequestService followRequestService;
    private final AccountHistoryService historyService;

    // ── Profile ───────────────────────────────────────────────────────────────

    @Override
    @Cacheable(value = CacheKeys.UserService.PROFILE_USERNAME, key = "#username")
    public Mono<UserResponse> getByUsername(String username) {
        return userRepo.findByUsernameAndIsDeletedFalse(username)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException(MessageKeys.USER_NOT_FOUND, username)))
                .map(this::toResponse);
    }

    @Override
    @Cacheable(value = CacheKeys.UserService.PROFILE, key = "#userId")
    public Mono<UserResponse> getById(UUID userId) {
        return userRepo.findByIdAndIsDeletedFalse(userId)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException(MessageKeys.USER_NOT_FOUND, userId)))
                .map(this::toResponse);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = CacheKeys.UserService.PROFILE,          key = "#principal.userId()"),
            @CacheEvict(value = CacheKeys.UserService.PROFILE_USERNAME, key = "#principal.username()")
    })
    public Mono<UserResponse> updateProfile(UserPrincipal principal, UpdateProfileRequest req) {
        return userRepo.findByIdAndIsDeletedFalse(principal.userId())
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException(MessageKeys.USER_NOT_FOUND, principal.userId())))
                .flatMap(user -> {
                    if (req.displayName() != null) user.setDisplayName(req.displayName());
                    if (req.bio()         != null) user.setBio(req.bio());
                    if (req.websiteUrl()  != null) user.setWebsiteUrl(req.websiteUrl());
                    if (req.location()    != null) user.setLocation(req.location());
                    if (req.isPrivate()   != null) user.setIsPrivate(req.isPrivate());
                    return userRepo.save(user);
                })
                .flatMap(user -> eventPublisher.publishProfileUpdated(user).thenReturn(user))
                .map(this::toResponse);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = CacheKeys.UserService.PROFILE,          key = "#principal.userId()"),
            @CacheEvict(value = CacheKeys.UserService.PROFILE_USERNAME, key = "#principal.username()")
    })
    public Mono<UserResponse> updateAvatar(UserPrincipal principal, String mediaId) {
        return userRepo.findByIdAndIsDeletedFalse(principal.userId())
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException(MessageKeys.USER_NOT_FOUND, principal.userId())))
                .flatMap(user -> {
                    user.setAvatarUrl(mediaId);
                    return userRepo.save(user);
                })
                .flatMap(user -> eventPublisher.publishAvatarChanged(user).thenReturn(user))
                .map(this::toResponse);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = CacheKeys.UserService.PROFILE,          key = "#principal.userId()"),
            @CacheEvict(value = CacheKeys.UserService.PROFILE_USERNAME, key = "#principal.username()")
    })
    public Mono<UserResponse> updateBackground(UserPrincipal principal, String mediaId) {
        return userRepo.findByIdAndIsDeletedFalse(principal.userId())
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException(MessageKeys.USER_NOT_FOUND, principal.userId())))
                .flatMap(user -> {
                    user.setBackgroundUrl(mediaId);
                    return userRepo.save(user);
                })
                .flatMap(user -> eventPublisher.publishBackgroundChanged(user).thenReturn(user))
                .map(this::toResponse);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = CacheKeys.UserService.PROFILE,          key = "#principal.userId()"),
            @CacheEvict(value = CacheKeys.UserService.PROFILE_USERNAME, key = "#principal.username()")
    })
    public Mono<UserResponse> updateSettings(UserPrincipal principal, UpdateSettingsRequest req) {
        return userRepo.findByIdAndIsDeletedFalse(principal.userId())
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException(MessageKeys.USER_NOT_FOUND, principal.userId())))
                .flatMap(user -> {
                    try {
                        if (req.themeSettings() != null)
                            user.setThemeSettings(objectMapper.writeValueAsString(req.themeSettings()));
                        if (req.notificationSettings() != null)
                            user.setNotificationSettings(
                                    objectMapper.writeValueAsString(req.notificationSettings()));
                        if (req.accountSettings() != null)
                            user.setAccountSettings(
                                    objectMapper.writeValueAsString(req.accountSettings()));
                    } catch (JsonProcessingException e) {
                        return Mono.error(new ValidationException("INVALID_SETTINGS_JSON"));
                    }
                    return userRepo.save(user);
                })
                .map(this::toResponse);
    }

    // ── Social graph ── delegates to FollowerService / FollowRequestService ───

    @Override
    public Mono<Void> follow(UserPrincipal principal, UUID targetId) {
        UUID followerId = principal.userId();
        if (followerId.equals(targetId)) {
            return Mono.error(new ConflictException("CANNOT_FOLLOW_SELF"));
        }
        return userRepo.findByIdAndIsDeletedFalse(targetId)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException(MessageKeys.USER_NOT_FOUND, targetId)))
                .flatMap(target -> Boolean.TRUE.equals(target.getIsPrivate())
                        ? followRequestService.create(followerId, targetId)
                        : followerService.follow(followerId, targetId, principal.username()));
    }

    @Override
    public Mono<Void> unfollow(UserPrincipal principal, UUID targetId) {
        return followerService.unfollow(principal.userId(), targetId);
    }

    @Override
    public Mono<PageResponse<UserResponse>> getFollowers(UUID userId, String cursor, int size) {
        return followerService.getFollowers(userId, cursor, size);
    }

    @Override
    public Mono<PageResponse<UserResponse>> getFollowing(UUID userId, String cursor, int size) {
        return followerService.getFollowing(userId, cursor, size);
    }

    // ── Follow requests ── delegates to FollowRequestService ─────────────────

    @Override
    public Mono<PageResponse<FollowRequestResponse>> getFollowRequests(UserPrincipal principal) {
        return followRequestService.getPending(principal.userId());
    }

    @Override
    public Mono<Void> respondFollowRequest(UserPrincipal principal, UUID reqId, String action) {
        return followRequestService.respond(principal.userId(), reqId, action);
    }

    // ── History ── delegates to AccountHistoryService ─────────────────────────

    @Override
    public Mono<PageResponse<AccountHistoryResponse>> getHistory(UserPrincipal principal) {
        return historyService.getHistory(principal.userId(), 50);
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @Override
    public Mono<PageResponse<UserResponse>> searchUsers(String query, int size) {
        if (query == null || query.length() < 2) {
            return Mono.just(PageResponse.empty());
        }
        return userRepo.searchByUsernameOrDisplayName(query, size)
                .map(this::toResponse)
                .collectList()
                .map(items -> PageResponse.of(items, items.size() >= size ? "next" : null));
    }

    // ── Verification ──────────────────────────────────────────────────────────

    @Override
    public Mono<Void> submitVerification(UserPrincipal principal, VerificationRequest req) {
        return verificationRepo.existsByUserIdAndStatus(principal.userId(), "PENDING")
                .flatMap(pending -> {
                    if (pending) {
                        return Mono.error(new ConflictException("VERIFICATION_ALREADY_PENDING"));
                    }
                    return userRepo.findByIdAndIsDeletedFalse(principal.userId())
                            .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                                    MessageKeys.USER_NOT_FOUND, principal.userId())))
                            .flatMap(user -> verificationRepo.save(Verification.builder()
                                            .id(UlidGenerator.generateAsUUID())
                                            .userId(principal.userId())
                                            .type(req.type())
                                            .documentUrl(req.documentMediaId())
                                            .status("PENDING")
                                            .createdAt(Instant.now())
                                            .build())
                                    .then());
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserResponse toResponse(io.github.lvoxx.user_service.entity.User u) {
        return new UserResponse(
                u.getId(), u.getUsername(), u.getDisplayName(), u.getBio(),
                u.getAvatarUrl(), u.getBackgroundUrl(), u.getWebsiteUrl(), u.getLocation(),
                u.getIsVerified(), u.getIsPrivate(),
                u.getFollowerCount(), u.getFollowingCount(), u.getPostCount(),
                u.getRole(), u.getCreatedAt());
    }
}
