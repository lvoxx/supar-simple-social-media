package com.lvoxx.sssm.user.service;

import com.lvoxx.sssm.user.domain.Profile;
import com.lvoxx.sssm.user.error.ConflictException;
import com.lvoxx.sssm.user.error.NotFoundException;
import com.lvoxx.sssm.user.repository.ProfileRepository;
import com.lvoxx.sssm.user.web.dto.CreateProfileRequest;
import com.lvoxx.sssm.user.web.dto.UpdateProfileRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profile lifecycle: linking a Keycloak identity to a profile, reading profiles, and editing the
 * caller's own profile. The caller's identity ({@code keycloakId}) always comes from the verified
 * JWT, never from request bodies.
 */
@Service
public class ProfileService {

    private final ProfileRepository profiles;

    public ProfileService(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    /**
     * Creates the profile for a Keycloak identity (the "Keycloak link"). Fails if this identity
     * already has a profile or the username is taken.
     */
    @Transactional
    public Profile create(UUID keycloakId, CreateProfileRequest req) {
        if (profiles.existsByKeycloakId(keycloakId)) {
            throw new ConflictException("A profile already exists for this account");
        }
        if (profiles.existsByUsername(req.username())) {
            throw new ConflictException("Username '" + req.username() + "' is already taken");
        }
        Profile profile = new Profile(keycloakId, req.username(), req.displayName());
        applyOptionalFields(profile, req);
        return profiles.save(profile);
    }

    @Transactional(readOnly = true)
    public Profile getByUsername(String username) {
        return profiles.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("No profile for username '" + username + "'"));
    }

    @Transactional(readOnly = true)
    public Profile getByKeycloakId(UUID keycloakId) {
        return profiles.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new NotFoundException("You have not created a profile yet"));
    }

    /** Updates the caller's own profile; {@code null} fields are left unchanged. */
    @Transactional
    public Profile update(UUID keycloakId, UpdateProfileRequest req) {
        Profile profile = getByKeycloakId(keycloakId);
        if (req.displayName() != null) {
            profile.setDisplayName(req.displayName());
        }
        if (req.bio() != null) {
            profile.setBio(req.bio());
        }
        if (req.avatarUrl() != null) {
            profile.setAvatarUrl(req.avatarUrl());
        }
        if (req.bannerUrl() != null) {
            profile.setBannerUrl(req.bannerUrl());
        }
        if (req.location() != null) {
            profile.setLocation(req.location());
        }
        if (req.website() != null) {
            profile.setWebsite(req.website());
        }
        return profile; // dirty-checking flushes on commit
    }

    /**
     * Deletes the caller's own profile. The {@code follows} FK cascade removes this user's edges;
     * counterparties' denormalized counts are reconciled asynchronously (out of MVP scope).
     */
    @Transactional
    public void delete(UUID keycloakId) {
        Profile profile = getByKeycloakId(keycloakId);
        profiles.delete(profile);
    }

    private static void applyOptionalFields(Profile profile, CreateProfileRequest req) {
        profile.setBio(req.bio());
        profile.setAvatarUrl(req.avatarUrl());
        profile.setBannerUrl(req.bannerUrl());
        profile.setLocation(req.location());
        profile.setWebsite(req.website());
    }
}
