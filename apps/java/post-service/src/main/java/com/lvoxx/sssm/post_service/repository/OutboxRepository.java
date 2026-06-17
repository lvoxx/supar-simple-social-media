package com.lvoxx.sssm.post_service.repository;

import com.lvoxx.sssm.post_service.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Unpublished events, oldest first, so the relay preserves per-aggregate ordering. */
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.createdAt asc")
    List<OutboxEvent> findUnpublished(Limit limit);
}
