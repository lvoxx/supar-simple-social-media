package com.lvoxx.sssm.user_service.repository;

import com.lvoxx.sssm.user_service.domain.Profile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    Optional<Profile> findByKeycloakId(UUID keycloakId);

    Optional<Profile> findByUsername(String username);

    boolean existsByKeycloakId(UUID keycloakId);

    boolean existsByUsername(String username);

    /**
     * Atomic denormalized-counter updates. Done as bulk UPDATEs (rather than read-modify-write on
     * the entity) so concurrent follows/unfollows can't lose increments. The decrements are
     * guarded so a count can never go negative if state ever drifts.
     */
    @Modifying
    @Query("update Profile p set p.followerCount = p.followerCount + 1 where p.id = :id")
    void incrementFollowerCount(@Param("id") UUID id);

    @Modifying
    @Query("update Profile p set p.followerCount = p.followerCount - 1 "
            + "where p.id = :id and p.followerCount > 0")
    void decrementFollowerCount(@Param("id") UUID id);

    @Modifying
    @Query("update Profile p set p.followingCount = p.followingCount + 1 where p.id = :id")
    void incrementFollowingCount(@Param("id") UUID id);

    @Modifying
    @Query("update Profile p set p.followingCount = p.followingCount - 1 "
            + "where p.id = :id and p.followingCount > 0")
    void decrementFollowingCount(@Param("id") UUID id);
}
