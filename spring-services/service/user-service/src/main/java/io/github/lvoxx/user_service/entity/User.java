package io.github.lvoxx.user_service.entity;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import io.github.lvoxx.common_core.model.SoftDeletableEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("users")
public class User extends SoftDeletableEntity {

    @Id
    private UUID id;
    private UUID keycloakId;
    private String username;
    private String displayName;
    private String bio;
    private String avatarUrl;
    private String backgroundUrl;
    private String websiteUrl;
    private String location;
    private LocalDate birthDate;
    @Builder.Default
    private Boolean isVerified = false;
    @Builder.Default
    private Boolean isPrivate = false;
    @Builder.Default
    private String role = "USER";
    @Builder.Default
    private Integer followerCount = 0;
    @Builder.Default
    private Integer followingCount = 0;
    @Builder.Default
    private Integer postCount = 0;
    @Builder.Default
    private String status = "ACTIVE";
    private String themeSettings;
    private String notificationSettings;
    private String accountSettings;
    private String fcmToken;
    private String apnsToken;
    @Builder.Default
    private Boolean pushEnabled = true;
    @Builder.Default
    private Boolean emailEnabled = true;
}
