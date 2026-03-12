package io.github.lvoxx.security_starter.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "sssm.security")
public class SecurityStarterProperties {

    /** Header injected by K8S Ingress — contains the user's ULID/UUID. */
    private String userIdHeader = "X-User-Id";

    /**
     * Header injected by K8S Ingress — comma-separated roles (e.g. USER,MODERATOR).
     */
    private String rolesHeader = "X-User-Roles";

    /** Header injected by K8S Ingress / proxy — original client IP. */
    private String ipHeader = "X-Forwarded-For";

    /** Paths that do not require an authenticated principal. */
    private List<String> anonymousPaths = List.of(
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui/**");
}