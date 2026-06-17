package com.lvoxx.sssm.user_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvoxx.sssm.user_service.domain.Profile;
import com.lvoxx.sssm.user_service.error.ConflictException;
import com.lvoxx.sssm.user_service.error.NotFoundException;
import com.lvoxx.sssm.user_service.repository.ProfileRepository;
import com.lvoxx.sssm.user_service.service.CursorPage;
import com.lvoxx.sssm.user_service.service.FollowService;
import com.lvoxx.sssm.user_service.service.ProfileService;
import com.lvoxx.sssm.user_service.support.PostgresIntegrationTest;
import com.lvoxx.sssm.user_service.web.dto.CreateProfileRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end persistence tests for the user-service core flow, exercising the real services,
 * repositories (including the {@code @Modifying} denormalized-count updates and keyset pagination
 * queries) and Hibernate mappings against a real PostgreSQL.
 */
class UserFlowIT extends PostgresIntegrationTest {

    @Autowired
    private ProfileService profiles;

    @Autowired
    private FollowService follows;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        // Container is shared across the class; reset graph + profiles between tests.
        jdbc.execute("TRUNCATE sssm.follows, sssm.profiles CASCADE");
    }

    private UUID createProfile(String username) {
        UUID keycloakId = UUID.randomUUID();
        profiles.create(keycloakId, new CreateProfileRequest(
                username, username + " display", null, null, null, null, null));
        return keycloakId;
    }

    @Test
    void create_persistsProfileWithZeroedCountsAndIsReadable() {
        UUID keycloakId = createProfile("alice");

        Profile byUsername = profiles.getByUsername("alice");
        assertThat(byUsername.getId()).isNotNull();
        assertThat(byUsername.getKeycloakId()).isEqualTo(keycloakId);
        assertThat(byUsername.getDisplayName()).isEqualTo("alice display");
        assertThat(byUsername.getFollowerCount()).isZero();
        assertThat(byUsername.getFollowingCount()).isZero();
        assertThat(byUsername.getCreatedAt()).isNotNull();

        assertThat(profiles.getByKeycloakId(keycloakId).getId()).isEqualTo(byUsername.getId());
    }

    @Test
    void create_rejectsDuplicateUsername() {
        createProfile("bob");
        assertThatThrownBy(() -> createProfile("bob"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void follow_incrementsDenormalizedCountsOnBothSides() {
        UUID alice = createProfile("alice");
        createProfile("bob");

        follows.follow(alice, "bob");

        assertThat(profileRepository.findByUsername("alice").orElseThrow().getFollowingCount())
                .isEqualTo(1);
        assertThat(profileRepository.findByUsername("bob").orElseThrow().getFollowerCount())
                .isEqualTo(1);

        // Edge is visible from both directions of the graph.
        assertThat(follows.following("alice", null, null).items())
                .extracting(Profile::getUsername).containsExactly("bob");
        assertThat(follows.followers("bob", null, null).items())
                .extracting(Profile::getUsername).containsExactly("alice");
    }

    @Test
    void follow_isRejectedWhenAlreadyFollowing() {
        UUID alice = createProfile("alice");
        createProfile("bob");
        follows.follow(alice, "bob");

        assertThatThrownBy(() -> follows.follow(alice, "bob"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void unfollow_decrementsCountsAndRemovesEdge() {
        UUID alice = createProfile("alice");
        createProfile("bob");
        follows.follow(alice, "bob");

        follows.unfollow(alice, "bob");

        assertThat(profileRepository.findByUsername("alice").orElseThrow().getFollowingCount())
                .isZero();
        assertThat(profileRepository.findByUsername("bob").orElseThrow().getFollowerCount())
                .isZero();
        assertThat(follows.followers("bob", null, null).items()).isEmpty();
        assertThatThrownBy(() -> follows.unfollow(alice, "bob"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void followers_arePaginatedNewestFirstWithoutDuplicatesOrGaps() {
        createProfile("star");
        int total = 5;
        for (int i = 0; i < total; i++) {
            UUID follower = createProfile("fan" + i);
            follows.follow(follower, "star");
        }

        // Walk the keyset cursor in pages of 2 and collect every follower exactly once.
        List<String> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            CursorPage<Profile> page = follows.followers("star", cursor, 2);
            page.items().forEach(p -> collected.add(p.getUsername()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        Set<String> expected = new HashSet<>();
        for (int i = 0; i < total; i++) {
            expected.add("fan" + i);
        }
        assertThat(collected).hasSize(total);
        assertThat(new HashSet<>(collected)).isEqualTo(expected);
        assertThat(profileRepository.findByUsername("star").orElseThrow().getFollowerCount())
                .isEqualTo(total);
    }
}
