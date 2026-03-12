package io.github.lvoxx.user_service.entity;

import lombok.*;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("followers")
public class Follower {
    private UUID followerId;
    private UUID followingId;
    private Instant createdAt;
}
