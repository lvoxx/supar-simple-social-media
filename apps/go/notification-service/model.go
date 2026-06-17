package main

import (
	"time"

	"github.com/google/uuid"
)

// Kind enumerates the notification types Slice 1 produces from post-events. Stored as TEXT so adding
// a kind later (follow, mention, ...) needs no migration.
const (
	KindLike     = "LIKE"
	KindRepost   = "REPOST"
	KindBookmark = "BOOKMARK"
	KindReply    = "REPLY"
)

// Notification is one item in a user's inbox. RecipientID is the user who receives it; ActorID is
// the user who triggered it. PostID is the subject post (for REPLY it is the PARENT post being
// replied to); ReplyPostID is the new reply post and is set only for REPLY. ReadAt is nil until the
// recipient marks it read. RecipientID is never serialized — it is always the authenticated caller.
type Notification struct {
	ID          uuid.UUID  `json:"id"`
	RecipientID uuid.UUID  `json:"-"`
	ActorID     uuid.UUID  `json:"actorId"`
	Kind        string     `json:"kind"`
	PostID      *uuid.UUID `json:"postId,omitempty"`
	ReplyPostID *uuid.UUID `json:"replyPostId,omitempty"`
	CreatedAt   time.Time  `json:"createdAt"`
	ReadAt      *time.Time `json:"readAt,omitempty"`
}

// NotificationPage is one keyset page of a user's inbox. NextCursor is empty when the inbox is
// exhausted. Mirrors timeline-service's page shape so clients paginate both feeds identically.
type NotificationPage struct {
	Items      []Notification `json:"items"`
	NextCursor string         `json:"nextCursor,omitempty"`
}

// Device is a registered push target for a user. The (Platform, Token) pair is globally unique — a
// physical device re-registering simply re-points the token at the current user.
type Device struct {
	UserID   uuid.UUID
	Platform string // "FCM" or "APNS"
	Token    string
}
