package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/lvoxx/sssm/go/common/eventv1"
)

// fakeRepo records calls and returns canned data so the orchestration is testable without Postgres.
type fakeRepo struct {
	authors        map[uuid.UUID]uuid.UUID // postID -> author; absence => errPostNotFound
	resolveErr     error
	insertConflict bool // when true, Insert reports a deduped (already-existing) notification

	inserted     []Notification
	list         []Notification
	listErr      error
	gotAfter     Cursor
	gotHasAfter  bool
	gotLimit     int
	unread       int64
	marked       int64
	deleted      int64
	deletedPost  uuid.UUID
	devices      []Device
	unregistered []Device
}

func (f *fakeRepo) ResolvePostAuthor(_ context.Context, postID uuid.UUID) (uuid.UUID, error) {
	if f.resolveErr != nil {
		return uuid.Nil, f.resolveErr
	}
	a, ok := f.authors[postID]
	if !ok {
		return uuid.Nil, errPostNotFound
	}
	return a, nil
}

func (f *fakeRepo) Insert(_ context.Context, n Notification) (Notification, bool, error) {
	f.inserted = append(f.inserted, n)
	if f.insertConflict {
		return Notification{}, false, nil
	}
	stored := n
	stored.ID = uuid.New()
	if stored.CreatedAt.IsZero() {
		stored.CreatedAt = time.Now().UTC()
	}
	return stored, true, nil
}

func (f *fakeRepo) List(_ context.Context, _ uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Notification, error) {
	f.gotAfter, f.gotHasAfter, f.gotLimit = after, hasAfter, limit
	if f.listErr != nil {
		return nil, f.listErr
	}
	if len(f.list) > limit {
		return f.list[:limit], nil
	}
	return f.list, nil
}

func (f *fakeRepo) UnreadCount(context.Context, uuid.UUID) (int64, error) { return f.unread, nil }
func (f *fakeRepo) MarkAllRead(context.Context, uuid.UUID) (int64, error) { return f.marked, nil }
func (f *fakeRepo) DeleteByPost(_ context.Context, postID uuid.UUID) (int64, error) {
	f.deletedPost = postID
	return f.deleted, nil
}
func (f *fakeRepo) RegisterDevice(_ context.Context, d Device) error {
	f.devices = append(f.devices, d)
	return nil
}
func (f *fakeRepo) UnregisterDevice(_ context.Context, platform, token string) error {
	f.unregistered = append(f.unregistered, Device{Platform: platform, Token: token})
	return nil
}

type fakePub struct{ published []Notification }

func (f *fakePub) Publish(_ uuid.UUID, n Notification) { f.published = append(f.published, n) }

type fakePusher struct{ pushed []Notification }

func (f *fakePusher) Push(_ context.Context, _ uuid.UUID, n Notification) {
	f.pushed = append(f.pushed, n)
}

func discardLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }
func testConfig() Config          { return Config{DefaultLimit: 2, MaxLimit: 100} }

func newSvc(repo *fakeRepo) (*NotificationService, *fakePub, *fakePusher) {
	pub, pusher := &fakePub{}, &fakePusher{}
	return NewNotificationService(testConfig(), repo, pub, pusher, discardLogger()), pub, pusher
}

func TestOnPostEngagementLikeNotifiesAuthor(t *testing.T) {
	post, author, actor := uuid.New(), uuid.New(), uuid.New()
	repo := &fakeRepo{authors: map[uuid.UUID]uuid.UUID{post: author}}
	svc, pub, pusher := newSvc(repo)

	err := svc.OnPostEngagement(context.Background(), eventv1.PostEngagement{
		PostID: post.String(), ActorID: actor.String(),
		Type: eventv1.EngagementLike, OccurredAt: time.Now().UTC(),
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(repo.inserted) != 1 {
		t.Fatalf("want 1 notification persisted, got %d", len(repo.inserted))
	}
	n := repo.inserted[0]
	if n.RecipientID != author || n.ActorID != actor || n.Kind != KindLike {
		t.Errorf("wrong notification: %+v", n)
	}
	if n.PostID == nil || *n.PostID != post {
		t.Errorf("PostID = %v, want %v", n.PostID, post)
	}
	if len(pub.published) != 1 || len(pusher.pushed) != 1 {
		t.Errorf("genuine insert must publish + push; pub=%d push=%d", len(pub.published), len(pusher.pushed))
	}
}

func TestOnPostEngagementSelfIsIgnored(t *testing.T) {
	post, author := uuid.New(), uuid.New()
	repo := &fakeRepo{authors: map[uuid.UUID]uuid.UUID{post: author}}
	svc, _, _ := newSvc(repo)

	_ = svc.OnPostEngagement(context.Background(), eventv1.PostEngagement{
		PostID: post.String(), ActorID: author.String(), Type: eventv1.EngagementLike,
	})
	if len(repo.inserted) != 0 {
		t.Error("self-engagement must not notify")
	}
}

func TestOnPostEngagementRemoveEventsIgnored(t *testing.T) {
	post, author, actor := uuid.New(), uuid.New(), uuid.New()
	repo := &fakeRepo{authors: map[uuid.UUID]uuid.UUID{post: author}}
	svc, _, _ := newSvc(repo)

	for _, typ := range []eventv1.EngagementType{
		eventv1.EngagementUnlike, eventv1.EngagementUnrepost,
		eventv1.EngagementUnbookmark, eventv1.EngagementView,
	} {
		_ = svc.OnPostEngagement(context.Background(), eventv1.PostEngagement{
			PostID: post.String(), ActorID: actor.String(), Type: typ,
		})
	}
	if len(repo.inserted) != 0 {
		t.Errorf("remove/view events must not notify, got %d", len(repo.inserted))
	}
}

func TestOnPostEngagementUnknownPostIgnored(t *testing.T) {
	repo := &fakeRepo{authors: map[uuid.UUID]uuid.UUID{}} // resolve => errPostNotFound
	svc, _, _ := newSvc(repo)

	err := svc.OnPostEngagement(context.Background(), eventv1.PostEngagement{
		PostID: uuid.NewString(), ActorID: uuid.NewString(), Type: eventv1.EngagementLike,
	})
	if err != nil {
		t.Fatalf("a missing post must be a no-op, not an error: %v", err)
	}
	if len(repo.inserted) != 0 {
		t.Error("must not notify for an unresolvable post")
	}
}

func TestOnPostEngagementDuplicateNotPublished(t *testing.T) {
	post, author, actor := uuid.New(), uuid.New(), uuid.New()
	repo := &fakeRepo{authors: map[uuid.UUID]uuid.UUID{post: author}, insertConflict: true}
	svc, pub, pusher := newSvc(repo)

	_ = svc.OnPostEngagement(context.Background(), eventv1.PostEngagement{
		PostID: post.String(), ActorID: actor.String(), Type: eventv1.EngagementLike,
	})
	if len(repo.inserted) != 1 {
		t.Fatal("Insert should still be attempted")
	}
	if len(pub.published) != 0 || len(pusher.pushed) != 0 {
		t.Error("a deduped redelivery must not publish or push again")
	}
}

func TestOnPostCreatedReplyNotifiesParentAuthor(t *testing.T) {
	parent, reply, parentAuthor, replier := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	repo := &fakeRepo{authors: map[uuid.UUID]uuid.UUID{parent: parentAuthor}}
	svc, pub, _ := newSvc(repo)

	err := svc.OnPostCreated(context.Background(), eventv1.PostCreated{
		PostID: reply.String(), AuthorID: replier.String(),
		ReplyToPostID: parent.String(), CreatedAt: time.Now().UTC(),
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(repo.inserted) != 1 {
		t.Fatalf("want 1 reply notification, got %d", len(repo.inserted))
	}
	n := repo.inserted[0]
	if n.RecipientID != parentAuthor || n.ActorID != replier || n.Kind != KindReply {
		t.Errorf("wrong reply notification: %+v", n)
	}
	if n.PostID == nil || *n.PostID != parent || n.ReplyPostID == nil || *n.ReplyPostID != reply {
		t.Errorf("reply notification should point at parent=%v reply=%v, got %+v", parent, reply, n)
	}
	if len(pub.published) != 1 {
		t.Error("reply notification should be published live")
	}
}

func TestOnPostCreatedTopLevelIgnored(t *testing.T) {
	repo := &fakeRepo{authors: map[uuid.UUID]uuid.UUID{}}
	svc, _, _ := newSvc(repo)
	err := svc.OnPostCreated(context.Background(), eventv1.PostCreated{
		PostID: uuid.NewString(), AuthorID: uuid.NewString(), // no ReplyToPostID
	})
	if err != nil || len(repo.inserted) != 0 {
		t.Errorf("a top-level post must not notify (err=%v inserted=%d)", err, len(repo.inserted))
	}
}

func TestOnPostCreatedSelfReplyIgnored(t *testing.T) {
	parent, author := uuid.New(), uuid.New()
	repo := &fakeRepo{authors: map[uuid.UUID]uuid.UUID{parent: author}}
	svc, _, _ := newSvc(repo)
	_ = svc.OnPostCreated(context.Background(), eventv1.PostCreated{
		PostID: uuid.NewString(), AuthorID: author.String(), ReplyToPostID: parent.String(),
	})
	if len(repo.inserted) != 0 {
		t.Error("replying to your own post must not notify you")
	}
}

func TestOnPostDeletedPurges(t *testing.T) {
	post := uuid.New()
	repo := &fakeRepo{deleted: 3}
	svc, _, _ := newSvc(repo)
	if err := svc.OnPostDeleted(context.Background(), eventv1.PostDeleted{PostID: post.String()}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if repo.deletedPost != post {
		t.Errorf("DeleteByPost called with %v, want %v", repo.deletedPost, post)
	}
}

func TestListPaginatesAndSetsCursor(t *testing.T) {
	base := time.Date(2026, 6, 17, 12, 0, 0, 0, time.UTC)
	mk := func(i int) Notification {
		return Notification{ID: uuid.New(), CreatedAt: base.Add(time.Duration(-i) * time.Minute)}
	}
	repo := &fakeRepo{list: []Notification{mk(0), mk(1), mk(2)}} // DefaultLimit=2 -> over-fetch 3
	svc, _, _ := newSvc(repo)

	page, err := svc.List(context.Background(), uuid.New(), "", 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if repo.gotLimit != 3 {
		t.Errorf("service should over-fetch limit+1=3, asked for %d", repo.gotLimit)
	}
	if len(page.Items) != 2 {
		t.Fatalf("want 2 items (limit), got %d", len(page.Items))
	}
	cur, ok, err := DecodeCursor(page.NextCursor)
	if err != nil || !ok {
		t.Fatalf("NextCursor not decodable: ok=%v err=%v", ok, err)
	}
	last := page.Items[1]
	if cur.ID != last.ID || !cur.CreatedAt.Equal(last.CreatedAt) {
		t.Errorf("NextCursor = %+v, want last item %v/%v", cur, last.CreatedAt, last.ID)
	}
}

func TestListLastPageHasNoCursor(t *testing.T) {
	repo := &fakeRepo{list: []Notification{{ID: uuid.New(), CreatedAt: time.Now().UTC()}}}
	svc, _, _ := newSvc(repo)
	page, err := svc.List(context.Background(), uuid.New(), "", 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if page.NextCursor != "" {
		t.Errorf("exhausted inbox should have no NextCursor, got %q", page.NextCursor)
	}
}

func TestListInvalidCursorRejected(t *testing.T) {
	svc, _, _ := newSvc(&fakeRepo{})
	_, err := svc.List(context.Background(), uuid.New(), "not-a-valid-cursor!!", 0)
	if !errors.Is(err, errInvalidCursor) {
		t.Errorf("want errInvalidCursor, got %v", err)
	}
}

func TestRegisterDeviceValidation(t *testing.T) {
	repo := &fakeRepo{}
	svc, _, _ := newSvc(repo)
	user := uuid.New()

	if err := svc.RegisterDevice(context.Background(), user, "TELEGRAM", "tok"); !errors.Is(err, errInvalidDevice) {
		t.Errorf("unsupported platform should be rejected, got %v", err)
	}
	if err := svc.RegisterDevice(context.Background(), user, "FCM", ""); !errors.Is(err, errInvalidDevice) {
		t.Errorf("empty token should be rejected, got %v", err)
	}
	if err := svc.RegisterDevice(context.Background(), user, "FCM", "tok-1"); err != nil {
		t.Fatalf("valid registration failed: %v", err)
	}
	if len(repo.devices) != 1 || repo.devices[0].UserID != user || repo.devices[0].Platform != "FCM" {
		t.Errorf("device not stored correctly: %+v", repo.devices)
	}
}
