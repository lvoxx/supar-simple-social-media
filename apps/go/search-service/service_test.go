package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/lvoxx/sssm/go/common/eventv1"
)

// fakeIndex records writes and returns a canned search result, so the service is tested without a
// live OpenSearch cluster (the fleet has none in the local toolchain).
type fakeIndex struct {
	docs        map[string]PostDoc
	deleted     []string
	searchQuery SearchQuery
	result      SearchResult
	searchErr   error
}

func newFakeIndex() *fakeIndex { return &fakeIndex{docs: map[string]PostDoc{}} }

func (f *fakeIndex) EnsureIndex(context.Context) error { return nil }

func (f *fakeIndex) IndexPost(_ context.Context, doc PostDoc) error {
	f.docs[doc.PostID] = doc
	return nil
}

func (f *fakeIndex) DeletePost(_ context.Context, id string) error {
	f.deleted = append(f.deleted, id)
	delete(f.docs, id)
	return nil
}

func (f *fakeIndex) Search(_ context.Context, q SearchQuery) (SearchResult, error) {
	f.searchQuery = q
	return f.result, f.searchErr
}

func testService(idx Index) *SearchService {
	cfg := Config{DefaultLimit: 20, MaxLimit: 50}
	return NewSearchService(cfg, idx, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

func TestOnPostCreatedIndexesDocument(t *testing.T) {
	idx := newFakeIndex()
	svc := testService(idx)
	now := time.Now().UTC().Truncate(time.Second)

	err := svc.OnPostCreated(context.Background(), eventv1.PostCreated{
		PostID:        "p1",
		AuthorID:      "a1",
		Text:          "hello world",
		MediaIDs:      []string{"m1"},
		ReplyToPostID: "parent",
		CreatedAt:     now,
	})
	if err != nil {
		t.Fatalf("OnPostCreated: %v", err)
	}
	got, ok := idx.docs["p1"]
	if !ok {
		t.Fatal("post not indexed")
	}
	if got.Text != "hello world" || got.AuthorID != "a1" || got.ReplyToPostID != "parent" {
		t.Fatalf("indexed doc mismatch: %+v", got)
	}
	if !got.CreatedAt.Equal(now) {
		t.Fatalf("created_at = %v, want %v", got.CreatedAt, now)
	}
}

func TestOnPostDeletedRemovesDocument(t *testing.T) {
	idx := newFakeIndex()
	idx.docs["p1"] = PostDoc{PostID: "p1"}
	svc := testService(idx)

	if err := svc.OnPostDeleted(context.Background(), eventv1.PostDeleted{PostID: "p1"}); err != nil {
		t.Fatalf("OnPostDeleted: %v", err)
	}
	if _, ok := idx.docs["p1"]; ok {
		t.Fatal("post still indexed after delete")
	}
	if len(idx.deleted) != 1 || idx.deleted[0] != "p1" {
		t.Fatalf("deleted = %v, want [p1]", idx.deleted)
	}
}

func TestSearchClampsLimitAndPassesQuery(t *testing.T) {
	idx := newFakeIndex()
	idx.result = SearchResult{Total: 0}
	svc := testService(idx)

	if _, err := svc.Search(context.Background(), "golang", "a1", 999, ""); err != nil {
		t.Fatalf("Search: %v", err)
	}
	if idx.searchQuery.Limit != 50 {
		t.Fatalf("limit = %d, want clamped to 50", idx.searchQuery.Limit)
	}
	if idx.searchQuery.Text != "golang" || idx.searchQuery.AuthorID != "a1" {
		t.Fatalf("query not forwarded: %+v", idx.searchQuery)
	}
	if idx.searchQuery.Offset != 0 {
		t.Fatalf("offset = %d, want 0 for empty cursor", idx.searchQuery.Offset)
	}
}

func TestSearchEmptyResultHasNoCursor(t *testing.T) {
	idx := newFakeIndex()
	idx.result = SearchResult{Total: 0, Hits: nil}
	svc := testService(idx)

	page, err := svc.Search(context.Background(), "nothing", "", 10, "")
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if page.NextCursor != "" {
		t.Fatalf("next cursor = %q, want empty", page.NextCursor)
	}
	if page.Items == nil {
		t.Fatal("items should be an empty slice, not nil")
	}
}

func TestSearchEmitsNextCursorWhenMoreRemain(t *testing.T) {
	idx := newFakeIndex()
	idx.result = SearchResult{
		Total: 25,
		Hits:  make([]SearchHit, 10),
	}
	svc := testService(idx)

	page, err := svc.Search(context.Background(), "go", "", 10, "")
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if page.NextCursor == "" {
		t.Fatal("expected a next cursor when more matches remain")
	}
	off, err := decodeCursor(page.NextCursor)
	if err != nil {
		t.Fatalf("decode next cursor: %v", err)
	}
	if off != 10 {
		t.Fatalf("next offset = %d, want 10", off)
	}
}

func TestSearchLastPageHasNoCursor(t *testing.T) {
	idx := newFakeIndex()
	// offset 20 + 5 hits == total 25: no further page.
	idx.result = SearchResult{Total: 25, Hits: make([]SearchHit, 5)}
	svc := testService(idx)

	page, err := svc.Search(context.Background(), "go", "", 10, encodeCursor(20))
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if idx.searchQuery.Offset != 20 {
		t.Fatalf("offset = %d, want 20 from cursor", idx.searchQuery.Offset)
	}
	if page.NextCursor != "" {
		t.Fatalf("next cursor = %q, want empty on last page", page.NextCursor)
	}
}

func TestSearchRejectsInvalidCursor(t *testing.T) {
	svc := testService(newFakeIndex())
	if _, err := svc.Search(context.Background(), "go", "", 10, "!!!not-base64!!!"); !errors.Is(err, errInvalidCursor) {
		t.Fatalf("err = %v, want errInvalidCursor", err)
	}
}

func TestSearchPropagatesIndexError(t *testing.T) {
	idx := newFakeIndex()
	idx.searchErr = errors.New("cluster down")
	svc := testService(idx)
	if _, err := svc.Search(context.Background(), "go", "", 10, ""); err == nil {
		t.Fatal("expected error to propagate from index")
	}
}

func TestCursorRoundTrip(t *testing.T) {
	for _, off := range []int{0, 1, 20, 12345} {
		got, err := decodeCursor(encodeCursor(off))
		if err != nil {
			t.Fatalf("decode(encode(%d)): %v", off, err)
		}
		if got != off {
			t.Fatalf("round trip = %d, want %d", got, off)
		}
	}
}

func TestDecodeCursorRejectsNegative(t *testing.T) {
	// base64 of "-5" decodes but must be rejected as an invalid offset.
	if _, err := decodeCursor(encodeBytes("-5")); !errors.Is(err, errInvalidCursor) {
		t.Fatalf("err = %v, want errInvalidCursor", err)
	}
}
