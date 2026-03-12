package io.github.lvoxx.user_service.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("account_history")
public class AccountHistory {
    @Id
    private UUID id;
    private UUID userId;
    private String action;
    private String detail;
    private String ip;
    private Instant createdAt;
}
