package main

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"unicode/utf8"

	"github.com/google/uuid"
)

// Validation errors the handler/session map to 4xx. errSelfMessage and the body checks are the only
// ways SendMessage can reject a request before it touches the database.
var (
	errInvalidCursor = errors.New("invalid cursor")
	errSelfMessage   = errors.New("cannot send a message to yourself")
	errEmptyBody     = errors.New("message body must not be empty")
	errBodyTooLong   = errors.New("message body too long")
)

// Repository is the persistence surface MessagingService drives. An interface so the service is
// unit-tested with a fake — never a live Postgres, matching the rest of the fleet.
type Repository interface {
	GetOrCreateConversation(ctx context.Context, a, b uuid.UUID) (Conversation, error)
	InsertMessage(ctx context.Context, m Message) (Message, error)
	ListConversations(ctx context.Context, userID uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Conversation, error)
	ListMessages(ctx context.Context, conversationID, userID uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Message, error)
}

// Publisher is the live fan-out surface: deliver a message to a user's connected sessions across all
// replicas. *RedisHub implements it; the service depends on the interface so it is testable with a fake
// and never imports Redis.
type Publisher interface {
	Publish(userID uuid.UUID, m Message)
}

// MessagingService is the core of messaging-service: it validates and persists DMs, fans them out live
// to both participants, and serves the conversation/message read APIs. It owns no transport — the WS
// session and the HTTP handler call into it.
type MessagingService struct {
	cfg Config
	repo Repository
	pub  Publisher
	log  *slog.Logger
}

func NewMessagingService(cfg Config, repo Repository, pub Publisher, log *slog.Logger) *MessagingService {
	return &MessagingService{cfg: cfg, repo: repo, pub: pub, log: log}
}

// SendMessage validates, persists, and live-delivers a DM from senderID to recipientID. The message is
// persisted FIRST (the durable record of record), then published to the recipient's channel (live
// delivery) and the sender's channel (so the sender's other devices/sessions stay in sync and the
// originating session gets a delivery echo). Publish is best-effort; a live-delivery failure never
// fails the send because the message is already durable.
func (s *MessagingService) SendMessage(ctx context.Context, senderID, recipientID uuid.UUID, body string) (Message, error) {
	if senderID == recipientID {
		return Message{}, errSelfMessage
	}
	body = strings.TrimSpace(body)
	if body == "" {
		return Message{}, errEmptyBody
	}
	if len(body) > s.cfg.MaxMessageBytes || !utf8.ValidString(body) {
		return Message{}, errBodyTooLong
	}

	conv, err := s.repo.GetOrCreateConversation(ctx, senderID, recipientID)
	if err != nil {
		return Message{}, err
	}

	stored, err := s.repo.InsertMessage(ctx, Message{
		ConversationID: conv.ID,
		SenderID:       senderID,
		RecipientID:    recipientID,
		Body:           body,
	})
	if err != nil {
		return Message{}, err
	}

	// Live fan-out to both participants. Recipient first (the point of a DM); sender second (multi-
	// device sync + an echo confirming the message landed).
	s.pub.Publish(recipientID, stored)
	s.pub.Publish(senderID, stored)
	return stored, nil
}

// ListConversations returns a keyset page of the caller's conversations, newest activity first, each
// projected to a ConversationView that names the other participant relative to the caller.
func (s *MessagingService) ListConversations(ctx context.Context, userID uuid.UUID, cursorToken string, limit int) (ConversationPage, error) {
	after, hasAfter, err := DecodeCursor(cursorToken)
	if err != nil {
		return ConversationPage{}, errInvalidCursor
	}
	limit = s.cfg.clampLimit(limit)

	convs, err := s.repo.ListConversations(ctx, userID, after, hasAfter, limit)
	if err != nil {
		return ConversationPage{}, err
	}

	page := ConversationPage{Items: make([]ConversationView, 0, len(convs))}
	for _, c := range convs {
		page.Items = append(page.Items, c.viewFor(userID))
	}
	if len(convs) == limit {
		last := convs[len(convs)-1]
		page.NextCursor = Cursor{TS: last.LastMessageAt, ID: last.ID}.Encode()
	}
	return page, nil
}

// ListMessages returns a keyset page of a conversation's messages, newest first. The repository's
// participant join means a non-participant simply gets an empty page — no separate authorization check
// and no way to probe a thread the caller is not in.
func (s *MessagingService) ListMessages(ctx context.Context, conversationID, userID uuid.UUID, cursorToken string, limit int) (MessagePage, error) {
	after, hasAfter, err := DecodeCursor(cursorToken)
	if err != nil {
		return MessagePage{}, errInvalidCursor
	}
	limit = s.cfg.clampLimit(limit)

	msgs, err := s.repo.ListMessages(ctx, conversationID, userID, after, hasAfter, limit)
	if err != nil {
		return MessagePage{}, err
	}

	page := MessagePage{Items: msgs}
	if page.Items == nil {
		page.Items = []Message{}
	}
	if len(msgs) == limit {
		last := msgs[len(msgs)-1]
		page.NextCursor = Cursor{TS: last.CreatedAt, ID: last.ID}.Encode()
	}
	return page, nil
}
