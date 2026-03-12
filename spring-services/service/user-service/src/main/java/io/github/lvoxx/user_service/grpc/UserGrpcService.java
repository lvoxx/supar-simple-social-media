package io.github.lvoxx.user_service.grpc;

import java.util.UUID;

import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.proto.user.CheckUserBlockedRequest;
import io.github.lvoxx.proto.user.CheckUserBlockedResponse;
import io.github.lvoxx.proto.user.FindUserByIdRequest;
import io.github.lvoxx.proto.user.FindUsersByIdsRequest;
import io.github.lvoxx.proto.user.GetNotifPreferencesRequest;
import io.github.lvoxx.proto.user.GetUserSettingsRequest;
import io.github.lvoxx.proto.user.NotifPreferencesResponse;
import io.github.lvoxx.proto.user.UserListResponse;
import io.github.lvoxx.proto.user.UserResponse;
import io.github.lvoxx.proto.user.UserSettingsResponse;
import io.github.lvoxx.proto.user.ReactorUserServiceGrpc;
import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.repository.UserRepository;
import io.github.lvoxx.user_service.service.BlockService;
import io.github.lvoxx.user_service.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class UserGrpcService extends ReactorUserServiceGrpc.UserServiceImplBase {

    private final UserRepository userRepository;
    private final BlockService blockService;
    private final UserSettingsService userSettingsService;

    @Override
    public Mono<UserResponse> findUserById(Mono<FindUserByIdRequest> request) {
        return request.flatMap(req ->
            userRepository.findByIdAndIsDeletedFalse(UUID.fromString(req.getUserId()))
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("USER_NOT_FOUND", req.getUserId())))
                .map(this::toProto)
        );
    }

    @Override
    public Mono<UserListResponse> findUsersByIds(Mono<FindUsersByIdsRequest> request) {
        return request.flatMap(req -> {
            var ids = req.getUserIdsList().stream()
                    .map(UUID::fromString)
                    .toList();
            return userRepository.findAllById(ids)
                .map(this::toProto)
                .collectList()
                .map(list -> UserListResponse.newBuilder().addAllUsers(list).build());
        });
    }

    @Override
    public Mono<CheckUserBlockedResponse> checkUserBlocked(Mono<CheckUserBlockedRequest> request) {
        return request.flatMap(req ->
            blockService.isBlocked(
                UUID.fromString(req.getBlockerId()),
                UUID.fromString(req.getBlockedId()))
                .map(blocked -> CheckUserBlockedResponse.newBuilder().setIsBlocked(blocked).build())
        );
    }

    @Override
    public Mono<UserSettingsResponse> getUserSettings(Mono<GetUserSettingsRequest> request) {
        return request.flatMap(req ->
            userSettingsService.getOrDefault(UUID.fromString(req.getUserId()))
                .map(s -> UserSettingsResponse.newBuilder()
                    .setReadReceipts(s.readReceipts())
                    .setOnlineStatus(s.onlineStatus())
                    .setNotificationLevel(s.notificationLevel())
                    .build())
        );
    }

    @Override
    public Mono<NotifPreferencesResponse> getNotifPreferences(Mono<GetNotifPreferencesRequest> request) {
        return request.flatMap(req ->
            userRepository.findByIdAndIsDeletedFalse(UUID.fromString(req.getUserId()))
                .map(u -> NotifPreferencesResponse.newBuilder()
                    .setFcmToken(u.getFcmToken() != null ? u.getFcmToken() : "")
                    .setApnsToken(u.getApnsToken() != null ? u.getApnsToken() : "")
                    .setPushEnabled(Boolean.TRUE.equals(u.getPushEnabled()))
                    .setEmailEnabled(Boolean.TRUE.equals(u.getEmailEnabled()))
                    .build())
                .defaultIfEmpty(NotifPreferencesResponse.newBuilder()
                    .setPushEnabled(true)
                    .setEmailEnabled(true)
                    .build())
        );
    }

    private UserResponse toProto(User user) {
        return UserResponse.newBuilder()
            .setUserId(user.getId().toString())
            .setUsername(user.getUsername())
            .setDisplayName(user.getDisplayName() != null ? user.getDisplayName() : "")
            .setAvatarUrl(user.getAvatarUrl() != null ? user.getAvatarUrl() : "")
            .setIsVerified(Boolean.TRUE.equals(user.getIsVerified()))
            .setIsPrivate(Boolean.TRUE.equals(user.getIsPrivate()))
            .setRole(user.getRole() != null ? user.getRole() : "USER")
            .setStatus(user.getStatus() != null ? user.getStatus() : "ACTIVE")
            .setCreatedAtMs(user.getCreatedAt() != null ? user.getCreatedAt().toEpochMilli() : 0L)
            .build();
    }
}
