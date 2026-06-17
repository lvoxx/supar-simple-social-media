package main

import (
	"context"
	"strconv"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
)

// dirtyKey holds the set of post IDs whose counters changed since the last flush, so the flusher
// writes only what moved.
const dirtyKey = "engagement:dirty"

func counterKey(postID uuid.UUID) string { return "engagement:" + postID.String() }

// redisCounters is the live counter store: one Redis hash per post (fields views/likes/reposts via
// HINCRBY) plus a dirty set. HINCRBY is atomic, so concurrent events from multiple replicas compose
// correctly without a lock.
type redisCounters struct {
	rdb *redis.Client
}

func newRedisCounters(rdb *redis.Client) *redisCounters { return &redisCounters{rdb: rdb} }

func (c *redisCounters) Apply(ctx context.Context, postID uuid.UUID, field string, delta int64) error {
	pipe := c.rdb.Pipeline()
	pipe.HIncrBy(ctx, counterKey(postID), field, delta)
	pipe.SAdd(ctx, dirtyKey, postID.String())
	_, err := pipe.Exec(ctx)
	return err
}

func (c *redisCounters) Get(ctx context.Context, postID uuid.UUID) (Metrics, bool, error) {
	vals, err := c.rdb.HGetAll(ctx, counterKey(postID)).Result()
	if err != nil {
		return Metrics{}, false, err
	}
	if len(vals) == 0 {
		return Metrics{}, false, nil
	}
	return Metrics{
		PostID:  postID,
		Views:   parseCounter(vals[FieldViews]),
		Likes:   parseCounter(vals[FieldLikes]),
		Reposts: parseCounter(vals[FieldReposts]),
	}, true, nil
}

func (c *redisCounters) Delete(ctx context.Context, postID uuid.UUID) error {
	pipe := c.rdb.Pipeline()
	pipe.Del(ctx, counterKey(postID))
	pipe.SRem(ctx, dirtyKey, postID.String())
	_, err := pipe.Exec(ctx)
	return err
}

func (c *redisCounters) DrainDirty(ctx context.Context, max int) ([]uuid.UUID, error) {
	raw, err := c.rdb.SPopN(ctx, dirtyKey, int64(max)).Result()
	if err != nil && err != redis.Nil {
		return nil, err
	}
	ids := make([]uuid.UUID, 0, len(raw))
	for _, s := range raw {
		if id, err := uuid.Parse(s); err == nil {
			ids = append(ids, id)
		}
	}
	return ids, nil
}

func (c *redisCounters) MarkDirty(ctx context.Context, ids []uuid.UUID) error {
	if len(ids) == 0 {
		return nil
	}
	members := make([]any, len(ids))
	for i, id := range ids {
		members[i] = id.String()
	}
	return c.rdb.SAdd(ctx, dirtyKey, members...).Err()
}

// parseCounter turns a Redis hash field into an int64, defaulting absent/garbage fields to 0.
func parseCounter(s string) int64 {
	if s == "" {
		return 0
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0
	}
	return n
}
