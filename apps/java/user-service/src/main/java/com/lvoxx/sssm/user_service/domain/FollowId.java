package com.lvoxx.sssm.user_service.domain;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Composite primary key for {@link Follow}: the {@code (follower_id, followee_id)} pair. Field
 * names and types must match the {@code @Id} fields on {@link Follow}.
 */
@Getter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class FollowId implements Serializable {

    private UUID followerId;
    private UUID followeeId;
}
