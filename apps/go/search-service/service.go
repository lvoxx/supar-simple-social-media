package main

import (
	"context"
	"encoding/base64"
	"errors"
	"log/slog"
	"strconv"

	"github.com/lvoxx/sssm/go/common/eventv1"
)

// errInvalidCursor is returned for a malformed pagination cursor; the handler maps it to 400.
var errInvalidCursor = errors.New("invalid cursor")

// SearchService keeps the OpenSearch index in sync with post-service's event stream and answers
// full-text queries over it. The index is a derived read model: it is written ONLY from Kafka
// (PostCreated indexes, PostDeleted removes) and never from the HTTP surface, so it can always be
// rebuilt by replaying the topic from the beginning.
type SearchService struct {
	cfg Config
	idx Index
	log *slog.Logger
}

func NewSearchService(cfg Config, idx Index, log *slog.Logger) *SearchService {
	return &SearchService{cfg: cfg, idx: idx, log: log}
}

// OnPostCreated indexes (or re-indexes) a post. Idempotent under at-least-once redelivery because the
// document is keyed by post id.
func (s *SearchService) OnPostCreated(ctx context.Context, e eventv1.PostCreated) error {
	return s.idx.IndexPost(ctx, PostDoc{
		PostID:        e.PostID,
		AuthorID:      e.AuthorID,
		Text:          e.Text,
		MediaIDs:      e.MediaIDs,
		ReplyToPostID: e.ReplyToPostID,
		CreatedAt:     e.CreatedAt,
	})
}

// OnPostDeleted removes a post from the index. A delete for a never-indexed post is a no-op.
func (s *SearchService) OnPostDeleted(ctx context.Context, e eventv1.PostDeleted) error {
	return s.idx.DeletePost(ctx, e.PostID)
}

// Search runs a paged full-text query. limit is clamped to the configured bounds; cursor is an
// opaque offset token (empty for the first page). The returned page carries a NextCursor only when
// more matches remain beyond the current window.
func (s *SearchService) Search(ctx context.Context, text, authorID string, limit int, cursor string) (SearchPage, error) {
	offset, err := decodeCursor(cursor)
	if err != nil {
		return SearchPage{}, err
	}
	limit = s.cfg.clampLimit(limit)

	res, err := s.idx.Search(ctx, SearchQuery{
		Text:     text,
		AuthorID: authorID,
		Limit:    limit,
		Offset:   offset,
	})
	if err != nil {
		return SearchPage{}, err
	}

	page := SearchPage{Items: res.Hits, Total: res.Total}
	if page.Items == nil {
		page.Items = []SearchHit{}
	}
	if next := offset + len(res.Hits); next < res.Total && len(res.Hits) > 0 {
		page.NextCursor = encodeCursor(next)
	}
	return page, nil
}

// encodeCursor packs an offset into an opaque base64 token so clients treat it as a handle rather
// than a number they can manipulate, consistent with the keyset cursors elsewhere in the fleet.
func encodeCursor(offset int) string {
	return base64.RawURLEncoding.EncodeToString([]byte(strconv.Itoa(offset)))
}

// decodeCursor unpacks a cursor token. An empty cursor means "first page" (offset 0).
func decodeCursor(cursor string) (int, error) {
	if cursor == "" {
		return 0, nil
	}
	raw, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, errInvalidCursor
	}
	offset, err := strconv.Atoi(string(raw))
	if err != nil || offset < 0 {
		return 0, errInvalidCursor
	}
	return offset, nil
}
