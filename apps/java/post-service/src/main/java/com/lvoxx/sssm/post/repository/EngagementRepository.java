package com.lvoxx.sssm.post.repository;

import com.lvoxx.sssm.post.domain.Engagement;
import com.lvoxx.sssm.post.domain.EngagementId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * CRUD for {@link Engagement} rows. The interesting operations — {@code existsById} (idempotency
 * check) and {@code deleteById} (unlike/unrepost/unbookmark) — are inherited; the composite
 * {@link EngagementId} is the key.
 */
public interface EngagementRepository extends JpaRepository<Engagement, EngagementId> {
}
