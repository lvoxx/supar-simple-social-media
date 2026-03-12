package io.github.lvoxx.user_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mappings;
import org.mapstruct.NullValuePropertyMappingStrategy;

import io.github.lvoxx.common_core.config.MapStructConfig;
import io.github.lvoxx.user_service.dto.UserResponse;
import io.github.lvoxx.user_service.entity.User;

@Mapper(config = MapStructConfig.class, nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface UserMapper {

    @Mapping(target = "createdAt", source = "createdAt")
    UserResponse toResponse(User user);

    @Mappings({
            @Mapping(target = "id", ignore = true),
            @Mapping(target = "keycloakId", ignore = true),
            @Mapping(target = "followerCount", ignore = true),
            @Mapping(target = "followingCount", ignore = true),
            @Mapping(target = "postCount", ignore = true),
            @Mapping(target = "status", ignore = true),
            @Mapping(target = "role", ignore = true),
            @Mapping(target = "isVerified", ignore = true),
            @Mapping(target = "themeSettings", ignore = true),
            @Mapping(target = "notificationSettings", ignore = true),
            @Mapping(target = "accountSettings", ignore = true),
            @Mapping(target = "createdAt", ignore = true),
            @Mapping(target = "updatedAt", ignore = true),
            @Mapping(target = "createdBy", ignore = true),
            @Mapping(target = "updatedBy", ignore = true),
            @Mapping(target = "isDeleted", ignore = true),
            @Mapping(target = "deletedAt", ignore = true),
            @Mapping(target = "deletedBy", ignore = true),
            @Mapping(target = "apnsToken", ignore = true),
            @Mapping(target = "birthDate", ignore = true),
            @Mapping(target = "emailEnabled", ignore = true),
            @Mapping(target = "fcmToken", ignore = true),
            @Mapping(target = "pushEnabled", ignore = true)
    })
    void updateFromResponse(UserResponse response, @MappingTarget User user);
}
