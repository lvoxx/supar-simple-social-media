package com.lvoxx.sssm.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lvoxx.sssm.user.domain.Profile;
import com.lvoxx.sssm.user.error.ConflictException;
import com.lvoxx.sssm.user.error.NotFoundException;
import com.lvoxx.sssm.user.repository.ProfileRepository;
import com.lvoxx.sssm.user.web.dto.CreateProfileRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private ProfileRepository profiles;

    @InjectMocks
    private ProfileService service;

    private final UUID keycloakId = UUID.randomUUID();

    @Test
    void create_persistsProfileLinkedToKeycloakIdentity() {
        var req = new CreateProfileRequest("alice", "Alice", "hi", null, null, null, null);
        when(profiles.existsByKeycloakId(keycloakId)).thenReturn(false);
        when(profiles.existsByUsername("alice")).thenReturn(false);
        when(profiles.save(any(Profile.class))).thenAnswer(inv -> inv.getArgument(0));

        Profile saved = service.create(keycloakId, req);

        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getDisplayName()).isEqualTo("Alice");
        assertThat(saved.getKeycloakId()).isEqualTo(keycloakId);
        assertThat(saved.getBio()).isEqualTo("hi");
    }

    @Test
    void create_rejectsSecondProfileForSameIdentity() {
        var req = new CreateProfileRequest("alice", "Alice", null, null, null, null, null);
        when(profiles.existsByKeycloakId(keycloakId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(keycloakId, req))
                .isInstanceOf(ConflictException.class);
        verify(profiles, never()).save(any());
    }

    @Test
    void create_rejectsTakenUsername() {
        var req = new CreateProfileRequest("alice", "Alice", null, null, null, null, null);
        when(profiles.existsByKeycloakId(keycloakId)).thenReturn(false);
        when(profiles.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.create(keycloakId, req))
                .isInstanceOf(ConflictException.class);
        verify(profiles, never()).save(any());
    }

    @Test
    void getByUsername_throwsWhenMissing() {
        when(profiles.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByUsername("ghost"))
                .isInstanceOf(NotFoundException.class);
    }
}
