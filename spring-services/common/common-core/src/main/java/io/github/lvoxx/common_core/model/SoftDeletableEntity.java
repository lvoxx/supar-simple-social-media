package io.github.lvoxx.common_core.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public abstract class SoftDeletableEntity extends AuditableEntity {
    protected Boolean isDeleted = false;
    protected Instant deletedAt;
    protected UUID deletedBy;

    protected boolean softDelete(UUID userId) {
        this.isDeleted = true;
        this.deletedAt = Instant.now();
        this.deletedBy = userId;
        return true;
    }
}
