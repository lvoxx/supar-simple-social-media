package io.github.lvoxx.websocket_starter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

import io.github.lvoxx.websocket_starter.properties.WebSocketProperties;

/**
 * Auto-configuration for reactive WebSocket support.
 *
 * <h3>What this starter provides:</h3>
 * <ul>
 * <li>{@link WebSocketHandlerAdapter} — integrates WebSocket handlers with
 * Spring WebFlux</li>
 * <li>{@link WebSocketService} — handshake service with configurable frame size
 * limits</li>
 * <li>{@link WebSocketProperties} bound to {@code sssm.websocket.*}</li>
 * </ul>
 *
 * <h3>Registering handlers:</h3>
 * Services declare their own handler mapping beans:
 * 
 * <pre>{@code
 * @Bean
 * public HandlerMapping wsHandlerMapping(NotificationWebSocketHandler handler) {
 *     return new SimpleUrlHandlerMapping(
 *             Map.of("/ws/notifications", handler), -1);
 * }
 * }</pre>
 *
 * <h3>Reading current user in a handler:</h3>
 * 
 * <pre>{@code
 * &#64;Override
 * public Mono<Void> handle(WebSocketSession session) {
 *     String userId = session.getHandshakeInfo().getHeaders().getFirst("X-User-Id");
 *     ...
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass(WebSocketHandler.class)
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAutoConfiguration.class);

    /**
     * WebSocket service with max message size configured from properties.
     */
    @Bean
    @ConditionalOnMissingBean(WebSocketService.class)
    public WebSocketService webSocketService(WebSocketProperties properties) {
        var strategy = new ReactorNettyRequestUpgradeStrategy();
        var service = new HandshakeWebSocketService(strategy);
        log.info("[websocket-starter] Registering WebSocketService maxTextSize={}B maxBinarySize={}B",
                properties.getMaxTextMessageSize(), properties.getMaxBinaryMessageSize());
        return service;
    }

    /**
     * WebSocket handler adapter — connects Spring WebFlux routing to WebSocket
     * handlers.
     */
    @Bean
    @ConditionalOnMissingBean(WebSocketHandlerAdapter.class)
    public WebSocketHandlerAdapter webSocketHandlerAdapter(WebSocketService webSocketService) {
        log.info("[websocket-starter] Registering WebSocketHandlerAdapter");
        return new WebSocketHandlerAdapter(webSocketService);
    }

    /**
     * Startup log marker.
     */
    @Bean
    public WebSocketStarterMarker webSocketStarterMarker(
            @Value("${spring.application.name:unknown-service}") String serviceName,
            WebSocketProperties properties) {
        log.info("[websocket-starter] Activated for service='{}' allowedOrigins={}",
                serviceName, properties.getAllowedOrigins());
        return new WebSocketStarterMarker(serviceName);
    }

    public record WebSocketStarterMarker(String serviceName) {
    }
}
