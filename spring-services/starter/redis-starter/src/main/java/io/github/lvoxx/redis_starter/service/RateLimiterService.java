package io.github.lvoxx.redis_starter.service;

import java.time.Duration;

import org.redisson.api.RRateLimiterReactive;
import org.redisson.api.RateType;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.stereotype.Service;

import io.github.lvoxx.common_core.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Distributed rate limiter backed by Redisson's token-bucket implementation.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class RateLimiterService {

    private final RedissonReactiveClient redissonClient;

    private final long capacity;
    private final long refillTokens;
    private final long refillPeriodSeconds;

    /**
     * Try to consume one token for the given key.
     */
    public Mono<Boolean> tryConsume(String key, String path) {
        String rateLimiterKey = "rate-limit:" + key + ":" + sanitisePath(path);
        RRateLimiterReactive rateLimiter = redissonClient.getRateLimiter(rateLimiterKey);

        Duration interval = Duration.ofSeconds(refillPeriodSeconds);

        return rateLimiter
                .trySetRate(RateType.PER_CLIENT, capacity, interval)
                .then(rateLimiter.tryAcquire(1))
                .doOnNext(allowed -> {
                    if (!allowed) {
                        log.warn("Rate limit exceeded for key={} path={}", key, path);
                    }
                });
    }

    /**
     * Try to consume one token; emits error if rate limited.
     */
    public Mono<Void> checkRateLimit(String key, String path) {
        return tryConsume(key, path)
                .flatMap(allowed -> allowed
                        ? Mono.empty()
                        : Mono.error(new RateLimitExceededException()));
    }

    private String sanitisePath(String path) {
        return path.replaceAll("[^a-zA-Z0-9/]", "_");
    }
}