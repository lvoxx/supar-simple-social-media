package io.github.lvoxx.post_service.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import io.github.lvoxx.common_core.model.SoftDeletableEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("posts")
public class Post extends SoftDeletableEntity {
    @Id
    private UUID id;
    private UUID authorId;
    private UUID groupId;
    private String content;
    private UUID replyToId;
    private UUID repostOfId;
    @Builder.Default
    private String postType = "ORIGINAL"; // ORIGINAL|REPLY|REPOST|QUOTE|AUTO
    @Builder.Default
    private String status = "PUBLISHED"; // DRAFT|PENDING_MEDIA|PUBLISHED|PENDING_REVIEW|HIDDEN|DELETED
    @Builder.Default
    private String visibility = "PUBLIC";
    @Builder.Default
    private Boolean isEdited = false;
    private Instant editedAt;
    @Builder.Default
    private Boolean isPinned = false;

    public boolean softDelete(UUID userId) {
        return super.softDelete(userId);
    }
}
