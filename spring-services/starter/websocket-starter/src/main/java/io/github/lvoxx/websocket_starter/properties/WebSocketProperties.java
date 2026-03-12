package io.github.lvoxx.websocket_starter.properties;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the WebSocket starter.
 */
@ConfigurationProperties(prefix = "sssm.websocket")
public class WebSocketProperties {

    /**
     * Allowed origins for WebSocket upgrade requests. Use {@code *} in dev only.
     */
    private List<String> allowedOrigins = List.of("*");

    /** Interval between server-side heartbeat pings to keep connections alive. */
    private Duration heartbeatInterval = Duration.ofSeconds(25);

    /** Maximum time a connection can be idle before being closed. */
    private Duration connectionTimeout = Duration.ofSeconds(60);

    /** Maximum size of a text (JSON) WebSocket message in bytes. */
    private int maxTextMessageSize = 65536;

    /** Maximum size of a binary WebSocket message in bytes. */
    private int maxBinaryMessageSize = 65536;

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getMaxTextMessageSize() {
        return maxTextMessageSize;
    }

    public void setMaxTextMessageSize(int maxTextMessageSize) {
        this.maxTextMessageSize = maxTextMessageSize;
    }

    public int getMaxBinaryMessageSize() {
        return maxBinaryMessageSize;
    }

    public void setMaxBinaryMessageSize(int maxBinaryMessageSize) {
        this.maxBinaryMessageSize = maxBinaryMessageSize;
    }
}