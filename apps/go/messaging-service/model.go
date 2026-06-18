package main

import (
	"time"

	"github.com/google/uuid"
)

// orderPair returns the two user ids in canonical (lexicographic) order so a conversation's identity
// is independent of who started it. The smaller id is user_lo, the larger user_hi — matching the
// dm_conversations CHECK (user_lo < user_hi) and its unique pair constraint.
func orderPair(a, b uuid.UUID) (lo, hi uuid.UUID) {
	if a.String() <= b.String() {
		return a, b
	}
	return b, a
}

// Conversation is a 1:1 DM thread between user_lo and user_hi (stored canonically ordered). It is
// never serialized directly — the API returns a ConversationView computed for the calling user so the
// client sees "who am I talking to" rather than the raw ordered pair.
type Conversation struct {
	ID            uuid.UUID
	UserLo        uuid.UUID
	UserHi        uuid.UUID
	CreatedAt     time.Time
	LastMessageAt time.Time
}

// other returns the participant of the conversation that is not me. Callers pass the authenticated
// subject; the result is the DM counterpart.
func (c Conversation) other(me uuid.UUID) uuid.UUID {
	if c.UserLo == me {
		return c.UserHi
	}
	return c.UserLo
}

// hasParticipant reports whether userID is one of the two participants.
func (c Conversation) hasParticipant(userID uuid.UUID) bool {
	return c.UserLo == userID || c.UserHi == userID
}

// ConversationView is the per-caller projection returned by the API. OtherUserID is the DM
// counterpart relative to the authenticated caller.
type ConversationView struct {
	ID            uuid.UUID `json:"id"`
	OtherUserID   uuid.UUID `json:"otherUserId"`
	CreatedAt     time.Time `json:"createdAt"`
	LastMessageAt time.Time `json:"lastMessageAt"`
}

func (c Conversation) viewFor(me uuid.UUID) ConversationView {
	return ConversationView{
		ID:            c.ID,
		OtherUserID:   c.other(me),
		CreatedAt:     c.CreatedAt,
		LastMessageAt: c.LastMessageAt,
	}
}

// Message is one DM. Both sender and recipient are meaningful (unlike a notification, whose recipient
// is always the caller), so both are serialized along with the conversation they belong to.
type Message struct {
	ID             uuid.UUID `json:"id"`
	ConversationID uuid.UUID `json:"conversationId"`
	SenderID       uuid.UUID `json:"senderId"`
	RecipientID    uuid.UUID `json:"recipientId"`
	Body           string    `json:"body"`
	CreatedAt      time.Time `json:"createdAt"`
}

// ConversationPage is one keyset page of a user's conversation list, newest activity first.
type ConversationPage struct {
	Items      []ConversationView `json:"items"`
	NextCursor string             `json:"nextCursor,omitempty"`
}

// MessagePage is one keyset page of a conversation's messages, newest first. Mirrors the page shape of
// the other services so clients paginate every feed identically.
type MessagePage struct {
	Items      []Message `json:"items"`
	NextCursor string    `json:"nextCursor,omitempty"`
}
