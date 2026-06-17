package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/google/uuid"
	"github.com/lvoxx/sssm/go/common/eventv1"
)

type applyCall struct {
	postID uuid.UUID
	field  string
	delta  int64
}

type fakeCounters struct {
	applied []applyCall
	store   map[uuid.UUID]Metrics
	getErr  error
	dirty   []uuid.UUID
	deleted []uuid.UUID
	marked  [][]uuid.UUID
}

func newFakeCounters() *fakeCounters { return &fakeCounters{store: map[uuid.UUID]Metrics{}} }

func (f *fakeCounters) Apply(_ context.Context, postID uuid.UUID, field string, delta int64) error {
	f.applied = append(f.applied, applyCall{postID, field, delta})
	return nil
}
func (f *fakeCounters) Get(_ context.Context, postID uuid.UUID) (Metrics, bool, error) {
	if f.getErr != nil {
		return Metrics{}, false, f.getErr
	}
	m, ok := f.store[postID]
	return m, ok, nil
}
func (f *fakeCounters) Delete(_ context.Context, postID uuid.UUID) error {
	f.deleted = append(f.deleted, postID)
	return nil
}
func (f *fakeCounters) DrainDirty(_ context.Context, _ int) ([]uuid.UUID, error) {
	ids := f.dirty
	f.dirty = nil
	return ids, nil
}
func (f *fakeCounters) MarkDirty(_ context.Context, ids []uuid.UUID) error {
	f.marked = append(f.marked, ids)
	return nil
}

type fakeRepo struct {
	upserted  [][]Metrics
	upsertErr error
	store     map[uuid.UUID]Metrics
	deleted   []uuid.UUID
}

func newFakeRepo() *fakeRepo { return &fakeRepo{store: map[uuid.UUID]Metrics{}} }

func (f *fakeRepo) Upsert(_ context.Context, metrics []Metrics) error {
	if f.upsertErr != nil {
		return f.upsertErr
	}
	f.upserted = append(f.upserted, metrics)
	return nil
}
func (f *fakeRepo) Get(_ context.Context, postID uuid.UUID) (Metrics, error) {
	m, ok := f.store[postID]
	if !ok {
		return Metrics{}, errMetricsNotFound
	}
	return m, nil
}
func (f *fakeRepo) Delete(_ context.Context, postID uuid.UUID) error {
	f.deleted = append(f.deleted, postID)
	return nil
}

func discardLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }
func testConfig() Config          { return Config{FlushBatch: 100} }

func newSvc() (*EngagementService, *fakeCounters, *fakeRepo) {
	c, r := newFakeCounters(), newFakeRepo()
	return NewEngagementService(testConfig(), c, r, discardLogger()), c, r
}

func TestOnPostEngagementMapsToDelta(t *testing.T) {
	post := uuid.New()
	tests := []struct {
		typ   eventv1.EngagementType
		field string
		delta int64
	}{
		{eventv1.EngagementLike, FieldLikes, 1},
		{eventv1.EngagementUnlike, FieldLikes, -1},
		{eventv1.EngagementRepost, FieldReposts, 1},
		{eventv1.EngagementUnrepost, FieldReposts, -1},
		{eventv1.EngagementView, FieldViews, 1},
	}
	for _, tt := range tests {
		svc, c, _ := newSvc()
		if err := svc.OnPostEngagement(context.Background(), eventv1.PostEngagement{
			PostID: post.String(), ActorID: uuid.NewString(), Type: tt.typ,
		}); err != nil {
			t.Fatalf("%v: %v", tt.typ, err)
		}
		if len(c.applied) != 1 || c.applied[0].field != tt.field || c.applied[0].delta != tt.delta {
			t.Errorf("%v -> applied %+v, want field=%s delta=%d", tt.typ, c.applied, tt.field, tt.delta)
		}
	}
}

func TestOnPostEngagementBookmarkIgnored(t *testing.T) {
	svc, c, _ := newSvc()
	for _, typ := range []eventv1.EngagementType{eventv1.EngagementBookmark, eventv1.EngagementUnbookmark, eventv1.EngagementUnspecified} {
		_ = svc.OnPostEngagement(context.Background(), eventv1.PostEngagement{
			PostID: uuid.NewString(), Type: typ,
		})
	}
	if len(c.applied) != 0 {
		t.Errorf("bookmark/unspecified events must not touch counters, got %d", len(c.applied))
	}
}

func TestOnPostDeletedDeletesBoth(t *testing.T) {
	post := uuid.New()
	svc, c, r := newSvc()
	if err := svc.OnPostDeleted(context.Background(), eventv1.PostDeleted{PostID: post.String()}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(c.deleted) != 1 || c.deleted[0] != post {
		t.Errorf("counter delete missing: %v", c.deleted)
	}
	if len(r.deleted) != 1 || r.deleted[0] != post {
		t.Errorf("snapshot delete missing: %v", r.deleted)
	}
}

func TestMetricsLiveFromCounters(t *testing.T) {
	post := uuid.New()
	svc, c, _ := newSvc()
	c.store[post] = Metrics{Likes: 5, Views: 9}
	m, err := svc.Metrics(context.Background(), post)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if m.Likes != 5 || m.Views != 9 || m.PostID != post {
		t.Errorf("want live counters, got %+v", m)
	}
}

func TestMetricsFallbackToSnapshot(t *testing.T) {
	post := uuid.New()
	svc, _, r := newSvc()
	r.store[post] = Metrics{PostID: post, Reposts: 3} // counters cold, snapshot has it
	m, err := svc.Metrics(context.Background(), post)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if m.Reposts != 3 {
		t.Errorf("want snapshot fallback, got %+v", m)
	}
}

func TestMetricsNeverEngagedReportsZeros(t *testing.T) {
	post := uuid.New()
	svc, _, _ := newSvc()
	m, err := svc.Metrics(context.Background(), post)
	if err != nil {
		t.Fatalf("a never-engaged post must report zeros, not error: %v", err)
	}
	if m.PostID != post || m.Likes != 0 || m.Views != 0 || m.Reposts != 0 {
		t.Errorf("want zeros for %v, got %+v", post, m)
	}
}

func TestMetricsCounterOutageFallsBackToSnapshot(t *testing.T) {
	post := uuid.New()
	svc, c, r := newSvc()
	c.getErr = errors.New("redis down")
	r.store[post] = Metrics{PostID: post, Likes: 2}
	m, err := svc.Metrics(context.Background(), post)
	if err != nil {
		t.Fatalf("Redis outage should fall back, not error: %v", err)
	}
	if m.Likes != 2 {
		t.Errorf("want snapshot on Redis outage, got %+v", m)
	}
}

func TestFlushUpsertsDirty(t *testing.T) {
	p1, p2 := uuid.New(), uuid.New()
	svc, c, r := newSvc()
	c.dirty = []uuid.UUID{p1, p2}
	c.store[p1] = Metrics{Likes: 1}
	c.store[p2] = Metrics{Views: 4}

	n, err := svc.Flush(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if n != 2 {
		t.Errorf("want 2 flushed, got %d", n)
	}
	if len(r.upserted) != 1 || len(r.upserted[0]) != 2 {
		t.Errorf("want one upsert of 2 metrics, got %+v", r.upserted)
	}
}

func TestFlushRequeuesOnUpsertError(t *testing.T) {
	p1 := uuid.New()
	svc, c, r := newSvc()
	c.dirty = []uuid.UUID{p1}
	c.store[p1] = Metrics{Likes: 1}
	r.upsertErr = errors.New("pg down")

	if _, err := svc.Flush(context.Background()); err == nil {
		t.Fatal("flush should surface the upsert error")
	}
	if len(c.marked) != 1 || len(c.marked[0]) != 1 || c.marked[0][0] != p1 {
		t.Errorf("failed flush must requeue the drained ids, got %+v", c.marked)
	}
}

func TestFlushEmptyIsNoop(t *testing.T) {
	svc, _, r := newSvc()
	n, err := svc.Flush(context.Background())
	if err != nil || n != 0 {
		t.Fatalf("empty flush: n=%d err=%v", n, err)
	}
	if len(r.upserted) != 0 {
		t.Error("empty flush must not upsert")
	}
}
