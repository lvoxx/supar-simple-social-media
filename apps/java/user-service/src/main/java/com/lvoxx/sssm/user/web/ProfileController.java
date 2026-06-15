package com.lvoxx.sssm.user.web;

import com.lvoxx.sssm.user.security.AuthenticatedUser;
import com.lvoxx.sssm.user.service.ProfileService;
import com.lvoxx.sssm.user.web.dto.CreateProfileRequest;
import com.lvoxx.sssm.user.web.dto.ProfileResponse;
import com.lvoxx.sssm.user.web.dto.UpdateProfileRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profile CRUD. Reads ({@code GET /{username}}) are public; everything scoped to "me" is derived
 * from the {@link AuthenticatedUser} (the gateway-forwarded Keycloak identity) and requires
 * authentication.
 */
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateProfileRequest req) {
        return ProfileResponse.from(profiles.create(user.id(), req));
    }

    @GetMapping("/me")
    public ProfileResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ProfileResponse.from(profiles.getByKeycloakId(user.id()));
    }

    @GetMapping("/{username}")
    public ProfileResponse byUsername(@PathVariable String username) {
        return ProfileResponse.from(profiles.getByUsername(username));
    }

    @PatchMapping("/me")
    public ProfileResponse update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UpdateProfileRequest req) {
        return ProfileResponse.from(profiles.update(user.id(), req));
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user) {
        profiles.delete(user.id());
    }
}
