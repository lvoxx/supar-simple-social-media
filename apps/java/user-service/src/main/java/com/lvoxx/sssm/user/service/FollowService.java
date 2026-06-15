package com.lvoxx.sssm.user.service;

import com.lvoxx.sssm.user.domain.Follow;
import com.lvoxx.sssm.user.domain.Profile;
import com.lvoxx.sssm.user.error.BadRequestException;
import com.lvoxx.sssm.user.error.ConflictException;
import com.lvoxx.sssm.user.error.NotFoundException;
import com.lvoxx.sssm.user.repository.FollowRepository;
import com.lvoxx.sssm.user.repository.ProfileRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Follow-graph operations: follow/unfollow (keeping denormalized counts in sync transactionally)
 * and cursor-paginated follower/following listings. The acting user is always identified by the
 * JWT subject ({@code keycloakId}); the target is identified by username.
 */
@Service
public class FollowService {

    /** Default and maximum page sizes for follower/following listings. */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ProfileRepository profiles;
    private final FollowRepository follows;

    public FollowService(ProfileRepository profiles, FollowRepository follows) {
        this.profiles = profiles;
        this.follows = follows;
    }

    @Transactional
    public void follow(UUID followerKeycloakId, String followeeUsername) {
        Profile follower = requireCaller(followerKeycloakId);
        Profile followee = requireUsername(followeeUsername);
        if (follower.getId().equals(followee.getId())) {
            throw new BadRequestException("You cannot follow yourself");
        }
        if (follows.existsByFollowerIdAndFolloweeId(follower.getId(), followee.getId())) {
            throw new ConflictException("You already follow @" + followeeUsername);
        }
        follows.save(new Follow(follower.getId(), followee.getId()));
        profiles.incrementFollowingCount(follower.getId());
        profiles.incrementFollowerCount(followee.getId());
    }

    @Transactional
    public void unfollow(UUID followerKeycloakId, String followeeUsername) {
        Profile follower = requireCaller(followerKeycloakId);
        Profile followee = requireUsername(followeeUsername);
        long removed = follows.deleteByFollowerIdAndFolloweeId(follower.getId(), followee.getId());
        if (removed == 0) {
            throw new NotFoundException("You are not following @" + followeeUsername);
        }
        profiles.decrementFollowingCount(follower.getId());
        profiles.decrementFollowerCount(followee.getId());
    }

    /** Profiles that follow {@code username}, newest first. */
    @Transactional(readOnly = true)
    public CursorPage<Profile> followers(String username, String cursor, Integer limit) {
        Profile target = requireUsername(username);
        Cursor c = Cursor.decode(cursor);
        int size = clampLimit(limit);
        List<Follow> rows = follows.pageFollowers(
                target.getId(), c == null ? null : c.ts(), c == null ? null : c.id(),
                Limit.of(size + 1));
        return toPage(rows, size, Follow::getFollowerId);
    }

    /** Profiles that {@code username} follows, newest first. */
    @Transactional(readOnly = true)
    public CursorPage<Profile> following(String username, String cursor, Integer limit) {
        Profile target = requireUsername(username);
        Cursor c = Cursor.decode(cursor);
        int size = clampLimit(limit);
        List<Follow> rows = follows.pageFollowing(
                target.getId(), c == null ? null : c.ts(), c == null ? null : c.id(),
                Limit.of(size + 1));
        return toPage(rows, size, Follow::getFolloweeId);
    }

    private Profile requireCaller(UUID keycloakId) {
        return profiles.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new NotFoundException("Create your profile before following others"));
    }

    private Profile requireUsername(String username) {
        return profiles.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("No profile for username '" + username + "'"));
    }

    /**
     * Turns a (size+1) row window into a page: drops the probe row, loads the referenced profiles
     * preserving follow order, and builds the next cursor from the last included row.
     */
    private CursorPage<Profile> toPage(
            List<Follow> rows, int size, Function<Follow, UUID> otherId) {
        boolean hasMore = rows.size() > size;
        List<Follow> page = hasMore ? rows.subList(0, size) : rows;

        List<UUID> ids = page.stream().map(otherId).toList();
        Map<UUID, Profile> byId = new LinkedHashMap<>();
        profiles.findAllById(ids).forEach(p -> byId.put(p.getId(), p));
        List<Profile> items = ids.stream().map(byId::get).filter(p -> p != null).toList();

        String next = null;
        if (hasMore && !page.isEmpty()) {
            Follow last = page.get(page.size() - 1);
            next = new Cursor(last.getCreatedAt(), otherId.apply(last)).encode();
        }
        return new CursorPage<>(items, next);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    /**
     * Opaque keyset cursor: the {@code created_at} and id of the last row of the previous page,
     * encoded as base64url of {@code "<instant>|<uuid>"}.
     */
    private record Cursor(Instant ts, UUID id) {

        String encode() {
            String raw = ts.toString() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(
                        Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int sep = raw.indexOf('|');
                return new Cursor(
                        Instant.parse(raw.substring(0, sep)),
                        UUID.fromString(raw.substring(sep + 1)));
            } catch (RuntimeException e) {
                throw new BadRequestException("Invalid cursor");
            }
        }
    }
}
