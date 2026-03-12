package io.github.lvoxx.user_service.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_blocks")
public class UserBlock {
    private UUID blockerId;
    private UUID blockedId;
    private Instant createdAt;
}
