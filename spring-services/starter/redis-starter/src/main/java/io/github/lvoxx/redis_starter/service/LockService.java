package io.github.lvoxx.redis_starter.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLockReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Reactive distributed-lock service backed by Redisson's reactive
 * {@link RLockReactive}.
 *
 * <p>
 * Usage — execute business logic under a lock:
 * 
 * <pre>{@code
 * lockService.withLock(LockKeys.follow(followerId, targetId), () ->
 *     followerRepo.existsByFollowerIdAndFollowingId(...)
 *         .flatMap(exists -> ...)
 * )
 * }</pre>
 *
 * <p>
 * If the lock cannot be acquired within {@code waitMs} milliseconds, the
 * supplier
 * is never called and the returned {@link Mono} completes empty — callers
 * should
 * use {@link Mono#switchIfEmpty} to surface a domain error.
 *
 * <p>
 * The lock is always released in a {@code doFinally} hook regardless of whether
 * the inner {@link Mono} succeeds, errors, or is cancelled.
 *
 * @see RateLimiterService
 */
@Slf4j
@RequiredArgsConstructor
public class LockService {

    private static final long DEFAULT_WAIT_MS = 500L;
    private static final long DEFAULT_LEASE_MS = 5_000L;

    private final RedissonReactiveClient redisson;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Acquire {@code key} with default wait (500 ms) and lease (5 s) timeouts.
     * Returns an empty {@link Mono} if the lock could not be acquired.
     *
     * @param key      the distributed lock key (use
     *                 {@link io.github.lvoxx.common.keys.LockKeys})
     * @param supplier reactive work to execute while holding the lock
     * @param <T>      return type of the inner mono
     */
    public <T> Mono<T> withLock(String key, Supplier<Mono<T>> supplier) {
        return withLock(key, DEFAULT_WAIT_MS, DEFAULT_LEASE_MS, supplier);
    }

    /**
     * Acquire {@code key} with explicit timeouts.
     *
     * @param key      distributed lock key
     * @param waitMs   maximum milliseconds to wait for the lock before giving up
     * @param leaseMs  maximum milliseconds to hold the lock (auto-release guard)
     * @param supplier reactive work to execute while holding the lock
     * @param <T>      return type of the inner mono
     * @return result of {@code supplier}, or empty if lock was not acquired
     */
    public <T> Mono<T> withLock(String key, long waitMs, long leaseMs,
            Supplier<Mono<T>> supplier) {
        RLockReactive lock = redisson.getLock(key);

        return lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS)
                .flatMap(acquired -> {
                    if (!acquired) {
                        log.warn("Could not acquire lock key={}", key);
                        return Mono.empty();
                    }
                    return supplier.get()
                            .doFinally(sig -> lock.unlock()
                                    .doOnError(e -> log.warn("Failed to release lock key={}: {}", key, e.getMessage()))
                                    .subscribe());
                });
    }

    /**
     * Convenience overload that returns {@link Mono}{@code <Void>} for write-only
     * operations.
     *
     * @param key      distributed lock key
     * @param supplier reactive work; must return {@code Mono<Void>}
     * @return empty mono on completion, or empty if lock was not acquired
     */
    public Mono<Void> withLockVoid(String key, Supplier<Mono<Void>> supplier) {
        return withLock(key, supplier);
    }
}
