# redis-starter

Auto-configures Redisson (reactive), Spring Cache (Redis backend), `LockService`, and `RateLimiterService`.

## What it provides

| Bean | Purpose |
|------|---------|
| `RedissonClient` | Synchronous Redisson client (Netty transport) |
| `RedissonReactiveClient` | Reactive wrapper |
| `LockService` | `withLock(key, supplier)` — distributed lock backed by `RLockReactive` |
| `RateLimiterService` | Token-bucket rate limiting per user via `RRateLimiterReactive` |

## LockService

```java
lockService.withLock(LockKeys.follow(followerId, targetId), () ->
    repo.existsByFollowerIdAndFollowingId(a, b)
        .flatMap(exists -> ...)
).switchIfEmpty(Mono.error(new ConflictException(...)));
```

Lock is always released in `doFinally`, even on cancellation.

## Default configuration (`application-redis.yaml`)

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

sssm:
  rate-limit:
    enabled: true
    default-capacity: 50
    default-refill-tokens: 50
    default-refill-period: 1m
```

## Properties

```yaml
sssm:
  rate-limit:
    enabled: true               # set false to disable globally
    default-capacity: 50        # bucket max tokens
    default-refill-tokens: 50   # refill amount
    default-refill-period: 1m   # refill interval
```

## Dependency

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>redis-starter</artifactId>
</dependency>
```
