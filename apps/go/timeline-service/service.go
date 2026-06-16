package main

import (
	"context"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

// Post is a single timeline entry. The denormalized counts are read straight off sssm.posts (kept
// in sync transactionally by post-service); timeline-service never writes them.
type Post struct {
	ID            uuid.UUID  `json:"id"`
	AuthorID      uuid.UUID  `json:"author_id"`
	Text          string     `json:"text"`
	ReplyToPostID *uuid.UUID `json:"reply_to_post_id,omitempty"`
	ReplyCount    int64      `json:"reply_count"`
	LikeCount     int64      `json:"like_count"`
	RepostCount   int64      `json:"repost_count"`
	BookmarkCount int64      `json:"bookmark_count"`
	CreatedAt     time.Time  `json:"created_at"`
}

// TimelinePage is one keyset page of the home feed. NextCursor is empty when the feed is exhausted.
type TimelinePage struct {
	Items      []Post `json:"items"`
	NextCursor string `json:"next_cursor,omitempty"`
}

// Repository reads the feed inputs from the shared Postgres (the budget single-RDS source of
// truth). It is an interface so the orchestration in TimelineService is unit-testable with fakes
// and so the Phase 2 migration to materialized Cassandra timelines is a drop-in swap.
type Repository interface {
	// Followees returns the IDs the user follows (the fan-out set). Self is added by the service.
	Followees(ctx context.Context, userID uuid.UUID) ([]uuid.UUID, error)
	// Posts returns up to limit posts authored by any of authorIDs, newest first, strictly older
	// than after when hasAfter is true. authorIDs is never empty when called.
	Posts(ctx context.Context, authorIDs []uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Post, error)
}

// Cache is the Redis layer in front of the two expensive reads: the follow-graph fan-out set and
// the materialized first page. A miss is never fatal — the service falls back to the repository.
type Cache interface {
	GetFollowees(ctx context.Context, userID uuid.UUID) ([]uuid.UUID, bool)
	SetFollowees(ctx context.Context, userID uuid.UUID, ids []uuid.UUID)
	GetFirstPage(ctx context.Context, userID uuid.UUID) (TimelinePage, bool)
	SetFirstPage(ctx context.Context, userID uuid.UUID, page TimelinePage)
}

// TimelineService assembles a user's home feed by fan-out-on-read: at request time it gathers the
// people the user follows and merges their most recent posts, ordered globally by recency. The
// follow-graph read and the first page are cached in Redis; deep (cursored) pages always hit
// Postgres because they are cold and not worth caching.
type TimelineService struct {
	cfg   Config
	repo  Repository
	cache Cache
	log   *slog.Logger
}

// NewTimelineService wires the orchestration. cache may be nil to run without a cache (e.g. tests).
func NewTimelineService(cfg Config, repo Repository, cache Cache, log *slog.Logger) *TimelineService {
	return &TimelineService{cfg: cfg, repo: repo, cache: cache, log: log}
}

// HomeTimeline returns one page of userID's home feed. token is the opaque cursor ("" = newest);
// limit is clamped to the configured bounds. The first page (no cursor) is served from and written
// to the cache; cursored pages bypass it.
func (s *TimelineService) HomeTimeline(ctx context.Context, userID uuid.UUID, token string, limit int) (TimelinePage, error) {
	after, hasAfter, err := DecodeCursor(token)
	if err != nil {
		return TimelinePage{}, errInvalidCursor
	}
	limit = s.cfg.clampLimit(limit)
	firstPage := !hasAfter

	// Fast path: a warm first page short-circuits both the graph and post reads.
	if firstPage && s.cache != nil {
		if page, ok := s.cache.GetFirstPage(ctx, userID); ok {
			return page, nil
		}
	}

	// The fan-out set always contains at least the user themselves (added by s.followees), so a
	// user who follows nobody still sees their own posts — there is no empty-set short-circuit.
	followees, err := s.followees(ctx, userID)
	if err != nil {
		return TimelinePage{}, err
	}

	// Over-fetch by one to learn whether a further page exists without a second COUNT query.
	posts, err := s.repo.Posts(ctx, followees, after, hasAfter, limit+1)
	if err != nil {
		return TimelinePage{}, err
	}

	page := TimelinePage{Items: posts}
	if len(posts) > limit {
		page.Items = posts[:limit]
		last := page.Items[len(page.Items)-1]
		page.NextCursor = Cursor{CreatedAt: last.CreatedAt, ID: last.ID}.Encode()
	}
	if page.Items == nil {
		page.Items = []Post{}
	}

	if firstPage && s.cache != nil {
		s.cache.SetFirstPage(ctx, userID, page)
	}
	return page, nil
}

// followees returns the fan-out set (people the user follows plus the user themselves, so their own
// posts appear), reading through the cache when available.
func (s *TimelineService) followees(ctx context.Context, userID uuid.UUID) ([]uuid.UUID, error) {
	if s.cache != nil {
		if ids, ok := s.cache.GetFollowees(ctx, userID); ok {
			return ids, nil
		}
	}
	ids, err := s.repo.Followees(ctx, userID)
	if err != nil {
		return nil, err
	}
	ids = append(ids, userID) // include self so the user sees their own posts in their feed
	if s.cache != nil {
		s.cache.SetFollowees(ctx, userID, ids)
	}
	return ids, nil
}
