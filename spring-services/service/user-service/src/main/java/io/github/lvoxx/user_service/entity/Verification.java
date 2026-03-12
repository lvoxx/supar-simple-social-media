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
@Table("verifications")
public class Verification {

    @Id
    private UUID id;
    private UUID userId;
    private String type;
    @Builder.Default
    private String status = "PENDING";
    // PENDING | APPROVED | REJECTED
    private String documentUrl;
    private UUID reviewedBy;
    private Instant createdAt;
    private Instant updatedAt;
}
