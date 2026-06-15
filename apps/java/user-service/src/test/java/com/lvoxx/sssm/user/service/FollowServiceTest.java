package com.lvoxx.sssm.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lvoxx.sssm.user.domain.Follow;
import com.lvoxx.sssm.user.domain.Profile;
import com.lvoxx.sssm.user.error.BadRequestException;
import com.lvoxx.sssm.user.error.ConflictException;
import com.lvoxx.sssm.user.error.NotFoundException;
import com.lvoxx.sssm.user.repository.FollowRepository;
import com.lvoxx.sssm.user.repository.ProfileRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock
    private ProfileRepository profiles;

    @Mock
    private FollowRepository follows;

    @InjectMocks
    private FollowService service;

    private final UUID followerKeycloakId = UUID.randomUUID();
    private final UUID followerId = UUID.randomUUID();
    private final UUID followeeId = UUID.randomUUID();

    /** Builds a Profile mock that reports the given id. Created as its own statement so the id
     * stubbing never nests inside another {@code thenReturn(...)}. */
    private static Profile profileWithId(UUID id) {
        Profile p = Mockito.mock(Profile.class);
        when(p.getId()).thenReturn(id);
        return p;
    }

    @Test
    void follow_createsEdgeAndUpdatesBothCounts() {
        Profile follower = profileWithId(followerId);
        Profile followee = profileWithId(followeeId);
        when(profiles.findByKeycloakId(followerKeycloakId)).thenReturn(Optional.of(follower));
        when(profiles.findByUsername("bob")).thenReturn(Optional.of(followee));
        when(follows.existsByFollowerIdAndFolloweeId(followerId, followeeId)).thenReturn(false);

        service.follow(followerKeycloakId, "bob");

        verify(follows).save(any(Follow.class));
        verify(profiles).incrementFollowingCount(followerId);
        verify(profiles).incrementFollowerCount(followeeId);
    }

    @Test
    void follow_rejectsSelfFollow() {
        Profile self = profileWithId(followerId);
        when(profiles.findByKeycloakId(followerKeycloakId)).thenReturn(Optional.of(self));
        when(profiles.findByUsername("me")).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> service.follow(followerKeycloakId, "me"))
                .isInstanceOf(BadRequestException.class);
        verify(follows, never()).save(any());
    }

    @Test
    void follow_rejectsDuplicate() {
        Profile follower = profileWithId(followerId);
        Profile followee = profileWithId(followeeId);
        when(profiles.findByKeycloakId(followerKeycloakId)).thenReturn(Optional.of(follower));
        when(profiles.findByUsername("bob")).thenReturn(Optional.of(followee));
        when(follows.existsByFollowerIdAndFolloweeId(followerId, followeeId)).thenReturn(true);

        assertThatThrownBy(() -> service.follow(followerKeycloakId, "bob"))
                .isInstanceOf(ConflictException.class);
        verify(follows, never()).save(any());
    }

    @Test
    void unfollow_failsWhenEdgeMissing() {
        Profile follower = profileWithId(followerId);
        Profile followee = profileWithId(followeeId);
        when(profiles.findByKeycloakId(followerKeycloakId)).thenReturn(Optional.of(follower));
        when(profiles.findByUsername("bob")).thenReturn(Optional.of(followee));
        when(follows.deleteByFollowerIdAndFolloweeId(followerId, followeeId)).thenReturn(0L);

        assertThatThrownBy(() -> service.unfollow(followerKeycloakId, "bob"))
                .isInstanceOf(NotFoundException.class);
        verify(profiles, never()).decrementFollowerCount(any());
    }

    @Test
    void follow_failsWhenCallerHasNoProfile() {
        when(profiles.findByKeycloakId(followerKeycloakId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.follow(followerKeycloakId, "bob"))
                .isInstanceOf(NotFoundException.class);
    }
}
