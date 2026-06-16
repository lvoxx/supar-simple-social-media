package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/google/uuid"
)

// fakeRepo records the arguments it is called with and returns canned data, so the fan-out
// orchestration can be tested without Postgres.
type fakeRepo struct {
	followees    []uuid.UUID
	followeesErr error
	posts        []Post

	postsCalled  bool
	gotAuthorIDs []uuid.UUID
	gotAfter     Cursor
	gotHasAfter  bool
	gotLimit     int
}

func (f *fakeRepo) Followees(_ context.Context, _ uuid.UUID) ([]uuid.UUID, error) {
	return f.followees, f.followeesErr
}

func (f *fakeRepo) Posts(_ context.Context, authorIDs []uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Post, error) {
	f.postsCalled = true
	f.gotAuthorIDs = authorIDs
	f.gotAfter = after
	f.gotHasAfter = hasAfter
	f.gotLimit = limit
	if len(f.posts) > limit {
		return f.posts[:limit], nil
	}
	return f.posts, nil
}

type fakeCache struct {
	firstPage    *TimelinePage
	setFirstPage *TimelinePage
}

func (f *fakeCache) GetFollowees(context.Context, uuid.UUID) ([]uuid.UUID, bool) { return nil, false }
func (f *fakeCache) SetFollowees(context.Context, uuid.UUID, []uuid.UUID)        {}
func (f *fakeCache) GetFirstPage(context.Context, uuid.UUID) (TimelinePage, bool) {
	if f.firstPage != nil {
		return *f.firstPage, true
	}
	return TimelinePage{}, false
}
func (f *fakeCache) SetFirstPage(_ context.Context, _ uuid.UUID, p TimelinePage) {
	f.setFirstPage = &p
}

func testConfig() Config { return Config{DefaultLimit: 2, MaxLimit: 100, CacheTTL: time.Second} }

func discardLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func mkPosts(n int) []Post {
	base := time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC)
	posts := make([]Post, n)
	for i := 0; i < n; i++ {
		posts[i] = Post{
			ID:        uuid.New(),
			AuthorID:  uuid.New(),
			Text:      "post",
			CreatedAt: base.Add(time.Duration(-i) * time.Minute), // newest first
		}
	}
	return posts
}

func TestHomeTimelineNoFollowsStillShowsOwnPosts(t *testing.T) {
	self := uuid.New()
	own := mkPosts(1)
	repo := &fakeRepo{followees: nil, posts: own} // follows nobody
	svc := NewTimelineService(testConfig(), repo, nil, discardLogger())

	page, err := svc.HomeTimeline(context.Background(), self, "", 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !repo.postsCalled {
		t.Fatal("Posts must still be queried — the user always sees their own posts")
	}
	if len(repo.gotAuthorIDs) != 1 || repo.gotAuthorIDs[0] != self {
		t.Errorf("fan-out set should be exactly [self], got %v", repo.gotAuthorIDs)
	}
	if len(page.Items) != 1 {
		t.Errorf("want the user's own 1 post, got %d", len(page.Items))
	}
}

func TestHomeTimelineFirstPagePaginatesAndIncludesSelf(t *testing.T) {
	self := uuid.New()
	followee := uuid.New()
	// limit defaults to 2; service over-fetches limit+1 = 3, so provide 3 posts.
	repo := &fakeRepo{followees: []uuid.UUID{followee}, posts: mkPosts(3)}
	svc := NewTimelineService(testConfig(), repo, nil, discardLogger())

	page, err := svc.HomeTimeline(context.Background(), self, "", 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(page.Items) != 2 {
		t.Fatalf("want 2 items (limit), got %d", len(page.Items))
	}
	if repo.gotLimit != 3 {
		t.Errorf("service should over-fetch limit+1=3, asked for %d", repo.gotLimit)
	}
	if !containsID(repo.gotAuthorIDs, self) {
		t.Error("fan-out set must include the user themselves")
	}
	if !containsID(repo.gotAuthorIDs, followee) {
		t.Error("fan-out set must include followees")
	}

	// NextCursor must point at the last returned item so the next page resumes after it.
	cur, ok, err := DecodeCursor(page.NextCursor)
	if err != nil || !ok {
		t.Fatalf("NextCursor not decodable: ok=%v err=%v", ok, err)
	}
	last := page.Items[1]
	if cur.ID != last.ID || !cur.CreatedAt.Equal(last.CreatedAt) {
		t.Errorf("NextCursor = %+v, want last item %v/%v", cur, last.CreatedAt, last.ID)
	}
}

func TestHomeTimelineLastPageHasNoCursor(t *testing.T) {
	repo := &fakeRepo{followees: []uuid.UUID{uuid.New()}, posts: mkPosts(2)} // fewer than limit+1
	svc := NewTimelineService(testConfig(), repo, nil, discardLogger())

	page, err := svc.HomeTimeline(context.Background(), uuid.New(), "", 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if page.NextCursor != "" {
		t.Errorf("exhausted feed should have no NextCursor, got %q", page.NextCursor)
	}
	if len(page.Items) != 2 {
		t.Errorf("want 2 items, got %d", len(page.Items))
	}
}

func TestHomeTimelineFirstPageServedFromCache(t *testing.T) {
	cached := TimelinePage{Items: []Post{{ID: uuid.New(), Text: "cached"}}}
	repo := &fakeRepo{followees: []uuid.UUID{uuid.New()}, posts: mkPosts(3)}
	cache := &fakeCache{firstPage: &cached}
	svc := NewTimelineService(testConfig(), repo, cache, discardLogger())

	page, err := svc.HomeTimeline(context.Background(), uuid.New(), "", 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(page.Items) != 1 || page.Items[0].Text != "cached" {
		t.Errorf("want cached page, got %#v", page.Items)
	}
	if repo.postsCalled {
		t.Error("a first-page cache hit must not query Postgres")
	}
}

func TestHomeTimelineCursoredPageBypassesCacheAndPassesCursor(t *testing.T) {
	cached := TimelinePage{Items: []Post{{Text: "stale-first-page"}}}
	repo := &fakeRepo{followees: []uuid.UUID{uuid.New()}, posts: mkPosts(1)}
	cache := &fakeCache{firstPage: &cached}
	svc := NewTimelineService(testConfig(), repo, cache, discardLogger())

	token := Cursor{
		CreatedAt: time.Date(2026, 6, 16, 11, 0, 0, 0, time.UTC),
		ID:        uuid.New(),
	}.Encode()

	page, err := svc.HomeTimeline(context.Background(), uuid.New(), token, 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !repo.postsCalled || !repo.gotHasAfter {
		t.Error("cursored page must query Postgres with hasAfter=true")
	}
	if len(page.Items) != 1 || page.Items[0].Text == "stale-first-page" {
		t.Error("cursored page must not be served from the first-page cache")
	}
	if cache.setFirstPage != nil {
		t.Error("cursored page must not overwrite the cached first page")
	}
}

func TestHomeTimelineInvalidCursorIsRejected(t *testing.T) {
	svc := NewTimelineService(testConfig(), &fakeRepo{}, nil, discardLogger())
	_, err := svc.HomeTimeline(context.Background(), uuid.New(), "not-a-valid-cursor!!", 0)
	if !errors.Is(err, errInvalidCursor) {
		t.Errorf("want errInvalidCursor, got %v", err)
	}
}

func TestHomeTimelineFirstPageWrittenToCache(t *testing.T) {
	repo := &fakeRepo{followees: []uuid.UUID{uuid.New()}, posts: mkPosts(1)}
	cache := &fakeCache{}
	svc := NewTimelineService(testConfig(), repo, cache, discardLogger())

	if _, err := svc.HomeTimeline(context.Background(), uuid.New(), "", 0); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cache.setFirstPage == nil {
		t.Error("first page should be written to the cache on a miss")
	}
}

func containsID(ids []uuid.UUID, want uuid.UUID) bool {
	for _, id := range ids {
		if id == want {
			return true
		}
	}
	return false
}
