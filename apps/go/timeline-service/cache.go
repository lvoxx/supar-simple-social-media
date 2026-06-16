package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
)

// redisCache implements Cache over go-redis. It is best-effort: every read miss or backend error
// degrades gracefully to a Postgres fan-out, so a Redis outage slows the timeline but never breaks
// it. Errors are logged at debug to avoid drowning logs when the cache is intentionally cold.
type redisCache struct {
	rdb *redis.Client
	ttl time.Duration
	log *slog.Logger
}

func newRedisCache(rdb *redis.Client, ttl time.Duration, log *slog.Logger) *redisCache {
	return &redisCache{rdb: rdb, ttl: ttl, log: log}
}

func followeesKey(userID uuid.UUID) string { return "timeline:followees:" + userID.String() }
func firstPageKey(userID uuid.UUID) string { return "timeline:home:" + userID.String() }

func (c *redisCache) GetFollowees(ctx context.Context, userID uuid.UUID) ([]uuid.UUID, bool) {
	var ids []uuid.UUID
	if c.get(ctx, followeesKey(userID), &ids) {
		return ids, true
	}
	return nil, false
}

func (c *redisCache) SetFollowees(ctx context.Context, userID uuid.UUID, ids []uuid.UUID) {
	c.set(ctx, followeesKey(userID), ids)
}

func (c *redisCache) GetFirstPage(ctx context.Context, userID uuid.UUID) (TimelinePage, bool) {
	var page TimelinePage
	if c.get(ctx, firstPageKey(userID), &page) {
		return page, true
	}
	return TimelinePage{}, false
}

func (c *redisCache) SetFirstPage(ctx context.Context, userID uuid.UUID, page TimelinePage) {
	c.set(ctx, firstPageKey(userID), page)
}

func (c *redisCache) get(ctx context.Context, key string, dst any) bool {
	raw, err := c.rdb.Get(ctx, key).Bytes()
	if err != nil {
		if err != redis.Nil {
			c.log.Debug("cache get failed", "key", key, "err", err)
		}
		return false
	}
	if err := json.Unmarshal(raw, dst); err != nil {
		c.log.Debug("cache decode failed", "key", key, "err", err)
		return false
	}
	return true
}

func (c *redisCache) set(ctx context.Context, key string, val any) {
	raw, err := json.Marshal(val)
	if err != nil {
		c.log.Debug("cache encode failed", "key", key, "err", err)
		return
	}
	if err := c.rdb.Set(ctx, key, raw, c.ttl).Err(); err != nil {
		c.log.Debug("cache set failed", "key", key, "err", err)
	}
}
