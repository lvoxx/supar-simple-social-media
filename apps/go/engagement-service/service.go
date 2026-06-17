package main

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/lvoxx/sssm/go/common/eventv1"
)

// Counter fields tracked per post. These are the high-throughput engagement signals (incl. views,
// which post-service does not persist on the post row); they are DISTINCT from post-service's
// transactional display counts and feed ranking in Phase 3.
const (
	FieldViews   = "views"
	FieldLikes   = "likes"
	FieldReposts = "reposts"
)

// errMetricsNotFound signals a post has no durable snapshot yet (never engaged). Read falls back to
// zeros rather than treating it as an error.
var errMetricsNotFound = errors.New("metrics not found")

// Metrics is the per-post counter set. Views/Likes/Reposts are monotone-ish aggregates maintained in
// Redis and snapshotted to Postgres.
type Metrics struct {
	PostID    uuid.UUID `json:"postId"`
	Views     int64     `json:"views"`
	Likes     int64     `json:"likes"`
	Reposts   int64     `json:"reposts"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// CounterStore is the Redis-backed live counter layer (the hot path). Apply also records the post in
// a dirty set so the flusher only writes changed posts. An interface so the orchestration is
// unit-testable without Redis.
type CounterStore interface {
	// Apply atomically adds delta to one field of postID's counters and marks the post dirty.
	Apply(ctx context.Context, postID uuid.UUID, field string, delta int64) error
	// Get reads the live counters; ok is false on a cold miss.
	Get(ctx context.Context, postID uuid.UUID) (Metrics, bool, error)
	Delete(ctx context.Context, postID uuid.UUID) error
	// DrainDirty atomically removes up to max post IDs from the dirty set and returns them.
	DrainDirty(ctx context.Context, max int) ([]uuid.UUID, error)
	// MarkDirty re-adds post IDs to the dirty set (used to requeue a failed flush).
	MarkDirty(ctx context.Context, ids []uuid.UUID) error
}

// MetricsRepository is the durable Postgres snapshot of the counters: survives Redis eviction/restart
// and is queryable in bulk/joins.
type MetricsRepository interface {
	Upsert(ctx context.Context, metrics []Metrics) error
	Get(ctx context.Context, postID uuid.UUID) (Metrics, error) // errMetricsNotFound when absent
	Delete(ctx context.Context, postID uuid.UUID) error
}

// EngagementService applies engagement events to the Redis counters and periodically flushes the
// dirty set to the Postgres snapshot. Reads serve live counters, falling back to the snapshot.
type EngagementService struct {
	cfg      Config
	counters CounterStore
	repo     MetricsRepository
	log      *slog.Logger
}

func NewEngagementService(cfg Config, counters CounterStore, repo MetricsRepository, log *slog.Logger) *EngagementService {
	return &EngagementService{cfg: cfg, counters: counters, repo: repo, log: log}
}

// OnPostEngagement maps the event to a counter delta and applies it. Bookmark events (private, owned
// by post-service) and unspecified types produce no counter change.
func (s *EngagementService) OnPostEngagement(ctx context.Context, e eventv1.PostEngagement) error {
	field, delta, ok := engagementDelta(e.Type)
	if !ok {
		return nil
	}
	postID, err := uuid.Parse(e.PostID)
	if err != nil {
		s.log.Warn("engagement event has invalid post_id", "post_id", e.PostID)
		return nil // poison message: drop rather than wedge the consumer
	}
	return s.counters.Apply(ctx, postID, field, delta)
}

// OnPostDeleted drops a deleted post's counters from both Redis and the snapshot. Both deletes are
// idempotent, so redelivery is harmless.
func (s *EngagementService) OnPostDeleted(ctx context.Context, e eventv1.PostDeleted) error {
	postID, err := uuid.Parse(e.PostID)
	if err != nil {
		s.log.Warn("delete event has invalid post_id", "post_id", e.PostID)
		return nil
	}
	if err := s.counters.Delete(ctx, postID); err != nil {
		return err
	}
	return s.repo.Delete(ctx, postID)
}

// Metrics returns a post's live counters, falling back to the durable snapshot on a Redis miss or
// outage; a post that was never engaged reports zeros.
func (s *EngagementService) Metrics(ctx context.Context, postID uuid.UUID) (Metrics, error) {
	m, ok, err := s.counters.Get(ctx, postID)
	if err == nil && ok {
		m.PostID = postID
		return m, nil
	}
	if err != nil {
		s.log.Warn("counter read failed; falling back to snapshot", "post_id", postID, "err", err)
	}

	snap, err := s.repo.Get(ctx, postID)
	if errors.Is(err, errMetricsNotFound) {
		return Metrics{PostID: postID}, nil
	}
	if err != nil {
		return Metrics{}, err
	}
	return snap, nil
}

// Flush drains a batch of dirty posts and upserts their live counters into the snapshot. On any
// failure the drained IDs are re-marked dirty so no update is lost — at the cost of re-flushing on
// the next tick (the upsert is idempotent).
func (s *EngagementService) Flush(ctx context.Context) (int, error) {
	ids, err := s.counters.DrainDirty(ctx, s.cfg.FlushBatch)
	if err != nil {
		return 0, err
	}
	if len(ids) == 0 {
		return 0, nil
	}

	metrics := make([]Metrics, 0, len(ids))
	for _, id := range ids {
		m, ok, err := s.counters.Get(ctx, id)
		if err != nil {
			_ = s.counters.MarkDirty(ctx, ids)
			return 0, err
		}
		if !ok {
			continue // counter vanished (e.g. concurrent delete) — nothing to snapshot
		}
		m.PostID = id
		m.UpdatedAt = time.Now().UTC()
		metrics = append(metrics, m)
	}
	if len(metrics) == 0 {
		return 0, nil
	}
	if err := s.repo.Upsert(ctx, metrics); err != nil {
		_ = s.counters.MarkDirty(ctx, ids)
		return 0, err
	}
	return len(metrics), nil
}

// engagementDelta maps an engagement type to the counter field and signed delta it affects.
// Bookmark/unbookmark are ignored (private; post-service owns them); unspecified is ignored.
func engagementDelta(t eventv1.EngagementType) (string, int64, bool) {
	switch t {
	case eventv1.EngagementLike:
		return FieldLikes, 1, true
	case eventv1.EngagementUnlike:
		return FieldLikes, -1, true
	case eventv1.EngagementRepost:
		return FieldReposts, 1, true
	case eventv1.EngagementUnrepost:
		return FieldReposts, -1, true
	case eventv1.EngagementView:
		return FieldViews, 1, true
	default:
		return "", 0, false
	}
}
