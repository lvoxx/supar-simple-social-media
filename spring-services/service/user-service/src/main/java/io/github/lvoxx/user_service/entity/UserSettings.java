package io.github.lvoxx.user_service.entity;

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
@Table("user_settings")
public class UserSettings {

    @Id
    private UUID userId;
    @Builder.Default
    private Boolean readReceipts = true;
    @Builder.Default
    private Boolean onlineStatus = true;
    @Builder.Default
    private String notificationLevel = "ALL";
}
