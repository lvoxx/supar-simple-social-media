package com.lvoxx.sssm.user.repository;

import com.lvoxx.sssm.user.domain.Follow;
import com.lvoxx.sssm.user.domain.FollowId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FollowRepository extends JpaRepository<Follow, FollowId> {

    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    @Modifying
    long deleteByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    /**
     * Followers of {@code followeeId}, newest first, with compound keyset pagination on
     * {@code (created_at, follower_id)} so rows sharing a {@code created_at} are never skipped.
     * Pass {@code ts}/{@code id} = null for the first page.
     */
    @Query("""
            select f from Follow f
            where f.followeeId = :followeeId
              and (:ts is null
                   or f.createdAt < :ts
                   or (f.createdAt = :ts and f.followerId < :id))
            order by f.createdAt desc, f.followerId desc
            """)
    List<Follow> pageFollowers(
            @Param("followeeId") UUID followeeId,
            @Param("ts") Instant ts,
            @Param("id") UUID id,
            Limit limit);

    /**
     * Profiles that {@code followerId} follows, newest first, keyset-paginated on
     * {@code (created_at, followee_id)}.
     */
    @Query("""
            select f from Follow f
            where f.followerId = :followerId
              and (:ts is null
                   or f.createdAt < :ts
                   or (f.createdAt = :ts and f.followeeId < :id))
            order by f.createdAt desc, f.followeeId desc
            """)
    List<Follow> pageFollowing(
            @Param("followerId") UUID followerId,
            @Param("ts") Instant ts,
            @Param("id") UUID id,
            Limit limit);
}
