package io.github.lvoxx.user_service.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("follow_requests")
public class FollowRequest {
    @Id
    private UUID id;
    private UUID requesterId;
    private UUID targetId;
    @Builder.Default
    private String status = "PENDING";
    private Instant createdAt;
}
