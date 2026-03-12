# websocket-starter

Auto-configures STOMP over WebSocket with Redis Pub/Sub for cross-pod message relay.

## Architecture

```
Client ──WS──▶ Pod A ──publish──▶ Redis Pub/Sub ──subscribe──▶ Pod B ──WS──▶ Client
```

Each pod subscribes to its connected users' channels. When a message arrives on Kafka
(e.g., `message.sent`), the service publishes to Redis Pub/Sub.
All pods receive the event and deliver it to their locally connected clients.

## What it provides

- STOMP endpoint at `/ws` (SockJS fallback enabled)
- `ReactiveRedisMessageListenerContainer` for cross-pod delivery
- `SimpMessagingTemplate` for server → client pushes

## Default configuration (`application-websocket.yaml`)

```yaml
sssm:
  websocket:
    allowed-origins: ${WS_ALLOWED_ORIGINS:http://localhost:3000}
    heartbeat-interval: 25s
    disconnect-delay: 5s
```

## Service-level override

```yaml
sssm:
  websocket:
    allowed-origins: ${WS_ALLOWED_ORIGINS:*}
```

## Client subscription patterns

| Service | Topic | Pattern |
|---------|-------|---------|
| notification-service | `/topic/notifications/{userId}` | Per-user notification stream |
| private-message-service | `/topic/messages/{convId}` | Per-conversation message stream |

## Dependency

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>websocket-starter</artifactId>
</dependency>
```
