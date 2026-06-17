package main

import (
	"context"
	"errors"
	"log/slog"

	"github.com/google/uuid"
	"github.com/lvoxx/sssm/go/common/eventv1"
)

// errInvalidCursor is returned when a client supplies a non-empty but unparseable cursor; the
// handler maps it to 400 so a bad token is a client error, not a silent reset to the first page.
var errInvalidCursor = errors.New("invalid cursor")

// errInvalidDevice is returned when a device registration is missing a token or names an unsupported
// platform; the handler maps it to 400.
var errInvalidDevice = errors.New("invalid device registration")

// errPostNotFound signals that a post referenced by an event no longer exists (or was never visible
// to this read-only consumer). The service treats it as "nothing to notify" rather than an error.
var errPostNotFound = errors.New("post not found")

// Repository persists notifications and device tokens (tables this service OWNS in the shared `sssm`
// schema) and resolves a post to its author by READING post-service's `sssm.posts` — the same
// cross-service read trade-off timeline-service makes under the single-RDS budget. An interface so
// the orchestration is unit-testable with fakes.
type Repository interface {
	// ResolvePostAuthor returns the author of postID, or errPostNotFound when the post is absent.
	ResolvePostAuthor(ctx context.Context, postID uuid.UUID) (uuid.UUID, error)
	// Insert persists n and returns the stored row; inserted is false when an identical notification
	// already exists (idempotent dedupe of at-least-once redelivery).
	Insert(ctx context.Context, n Notification) (stored Notification, inserted bool, err error)
	// List returns one keyset page of recipientID's inbox, newest first, strictly older than after
	// when hasAfter is true.
	List(ctx context.Context, recipientID uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Notification, error)
	UnreadCount(ctx context.Context, recipientID uuid.UUID) (int64, error)
	// MarkAllRead stamps every unread notification of recipientID read; returns the count updated.
	MarkAllRead(ctx context.Context, recipientID uuid.UUID) (int64, error)
	// DeleteByPost removes notifications referencing postID (post deletion cleanup); returns the count.
	DeleteByPost(ctx context.Context, postID uuid.UUID) (int64, error)
	RegisterDevice(ctx context.Context, d Device) error
	UnregisterDevice(ctx context.Context, platform, token string) error
}

// Publisher fans a freshly persisted notification out to the recipient's live SSE streams. *Hub
// implements it; tests use a recording fake.
type Publisher interface {
	Publish(recipientID uuid.UUID, n Notification)
}

// NotificationService turns post-events into per-user notifications: it resolves the recipient,
// persists the notification idempotently, and (on a genuine insert) pushes it to live SSE streams
// and the push provider. It also serves the inbox read/ack API and device registration.
type NotificationService struct {
	cfg    Config
	repo   Repository
	hub    Publisher
	pusher Pusher
	log    *slog.Logger
}

func NewNotificationService(cfg Config, repo Repository, hub Publisher, pusher Pusher, log *slog.Logger) *NotificationService {
	return &NotificationService{cfg: cfg, repo: repo, hub: hub, pusher: pusher, log: log}
}

// OnPostEngagement notifies a post's author that someone liked/reposted/bookmarked it. Remove events
// (unlike/unrepost/unbookmark), views, and self-engagement produce nothing.
func (s *NotificationService) OnPostEngagement(ctx context.Context, e eventv1.PostEngagement) error {
	kind, ok := engagementKind(e.Type)
	if !ok {
		return nil // unlike/unrepost/unbookmark/view/unspecified — not notifiable
	}
	postID, actorID, ok := s.parseIDs(e.PostID, e.ActorID, "engagement")
	if !ok {
		return nil // poison message: drop rather than wedge the consumer
	}

	author, err := s.repo.ResolvePostAuthor(ctx, postID)
	if errors.Is(err, errPostNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	if author == actorID {
		return nil // no "you liked your own post"
	}

	return s.deliver(ctx, Notification{
		RecipientID: author,
		ActorID:     actorID,
		Kind:        kind,
		PostID:      &postID,
		CreatedAt:   e.OccurredAt,
	})
}

// OnPostCreated notifies the parent post's author of a reply. Top-level posts (no reply_to) and
// self-replies produce nothing.
func (s *NotificationService) OnPostCreated(ctx context.Context, e eventv1.PostCreated) error {
	if e.ReplyToPostID == "" {
		return nil // only replies notify; a plain post fans out via timeline-service, not here
	}
	parentID, replierID, ok := s.parseIDs(e.ReplyToPostID, e.AuthorID, "reply")
	if !ok {
		return nil
	}
	replyID, err := uuid.Parse(e.PostID)
	if err != nil {
		s.log.Warn("reply event has invalid post_id", "post_id", e.PostID)
		return nil
	}

	parentAuthor, err := s.repo.ResolvePostAuthor(ctx, parentID)
	if errors.Is(err, errPostNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	if parentAuthor == replierID {
		return nil // replying to yourself does not notify you
	}

	return s.deliver(ctx, Notification{
		RecipientID: parentAuthor,
		ActorID:     replierID,
		Kind:        KindReply,
		PostID:      &parentID,
		ReplyPostID: &replyID,
		CreatedAt:   e.CreatedAt,
	})
}

// OnPostDeleted purges any notifications that referenced the deleted post so a tap can't deep-link
// into a 404.
func (s *NotificationService) OnPostDeleted(ctx context.Context, e eventv1.PostDeleted) error {
	postID, err := uuid.Parse(e.PostID)
	if err != nil {
		s.log.Warn("delete event has invalid post_id", "post_id", e.PostID)
		return nil
	}
	n, err := s.repo.DeleteByPost(ctx, postID)
	if err != nil {
		return err
	}
	if n > 0 {
		s.log.Info("purged notifications for deleted post", "post_id", postID, "count", n)
	}
	return nil
}

// deliver persists the notification, then — only when it was a genuine insert (not a deduped
// redelivery) — publishes it to live SSE streams and the push provider.
func (s *NotificationService) deliver(ctx context.Context, n Notification) error {
	stored, inserted, err := s.repo.Insert(ctx, n)
	if err != nil {
		return err
	}
	if !inserted {
		return nil // identical notification already delivered (at-least-once dedupe)
	}
	s.hub.Publish(stored.RecipientID, stored)
	s.pusher.Push(ctx, stored.RecipientID, stored)
	return nil
}

// List returns one page of recipientID's inbox. token is the opaque cursor ("" = newest); limit is
// clamped to the configured bounds.
func (s *NotificationService) List(ctx context.Context, recipientID uuid.UUID, token string, limit int) (NotificationPage, error) {
	after, hasAfter, err := DecodeCursor(token)
	if err != nil {
		return NotificationPage{}, errInvalidCursor
	}
	limit = s.cfg.clampLimit(limit)

	// Over-fetch by one to learn whether a further page exists without a second COUNT query.
	items, err := s.repo.List(ctx, recipientID, after, hasAfter, limit+1)
	if err != nil {
		return NotificationPage{}, err
	}

	page := NotificationPage{Items: items}
	if len(items) > limit {
		page.Items = items[:limit]
		last := page.Items[len(page.Items)-1]
		page.NextCursor = Cursor{CreatedAt: last.CreatedAt, ID: last.ID}.Encode()
	}
	if page.Items == nil {
		page.Items = []Notification{}
	}
	return page, nil
}

func (s *NotificationService) UnreadCount(ctx context.Context, recipientID uuid.UUID) (int64, error) {
	return s.repo.UnreadCount(ctx, recipientID)
}

func (s *NotificationService) MarkAllRead(ctx context.Context, recipientID uuid.UUID) (int64, error) {
	return s.repo.MarkAllRead(ctx, recipientID)
}

// RegisterDevice validates and stores a push target for userID.
func (s *NotificationService) RegisterDevice(ctx context.Context, userID uuid.UUID, platform, token string) error {
	if !validPlatform(platform) || token == "" {
		return errInvalidDevice
	}
	return s.repo.RegisterDevice(ctx, Device{UserID: userID, Platform: platform, Token: token})
}

func (s *NotificationService) UnregisterDevice(ctx context.Context, platform, token string) error {
	if !validPlatform(platform) || token == "" {
		return errInvalidDevice
	}
	return s.repo.UnregisterDevice(ctx, platform, token)
}

// parseIDs parses the two id fields an event carries, logging and signalling drop on malformed input.
func (s *NotificationService) parseIDs(rawA, rawB, kind string) (uuid.UUID, uuid.UUID, bool) {
	a, err := uuid.Parse(rawA)
	if err != nil {
		s.log.Warn("event has invalid id", "kind", kind, "field", "a", "value", rawA)
		return uuid.Nil, uuid.Nil, false
	}
	b, err := uuid.Parse(rawB)
	if err != nil {
		s.log.Warn("event has invalid id", "kind", kind, "field", "b", "value", rawB)
		return uuid.Nil, uuid.Nil, false
	}
	return a, b, true
}

// engagementKind maps the notifiable engagement adds to a notification kind. Remove events and views
// are not notifiable.
func engagementKind(t eventv1.EngagementType) (string, bool) {
	switch t {
	case eventv1.EngagementLike:
		return KindLike, true
	case eventv1.EngagementRepost:
		return KindRepost, true
	case eventv1.EngagementBookmark:
		return KindBookmark, true
	default:
		return "", false
	}
}

func validPlatform(p string) bool { return p == "FCM" || p == "APNS" }
