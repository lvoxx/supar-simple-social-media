package com.lvoxx.sssm.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A user's public profile. Maps 1:1 to the infrastructure-owned {@code sssm.profiles} table
 * (see {@code deploy/migrations/user-service/V1__baseline.sql}). The app runs with
 * {@code ddl-auto=validate} and never creates or alters this table, so every column mapped here
 * must match the baseline migration exactly.
 *
 * <p>{@code keycloakId} is the link to the Keycloak identity (the JWT {@code sub}); it is set once
 * at creation and never changed. {@code followerCount}/{@code followingCount} are denormalized for
 * read speed and kept in sync transactionally by the follow flow.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "profiles")
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "keycloak_id", nullable = false, unique = true, updatable = false)
    private UUID keycloakId;

    @Column(name = "username", nullable = false, unique = true, length = 30, updatable = false)
    private String username;

    @Setter
    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Setter
    @Column(name = "bio", length = 160)
    private String bio;

    @Setter
    @Column(name = "avatar_url")
    private String avatarUrl;

    @Setter
    @Column(name = "banner_url")
    private String bannerUrl;

    @Setter
    @Column(name = "location", length = 60)
    private String location;

    @Setter
    @Column(name = "website", length = 120)
    private String website;

    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    @Column(name = "follower_count", nullable = false)
    private long followerCount = 0;

    @Column(name = "following_count", nullable = false)
    private long followingCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Profile(UUID keycloakId, String username, String displayName) {
        this.keycloakId = keycloakId;
        this.username = username;
        this.displayName = displayName;
    }
}
