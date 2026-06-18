package main

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

// fakeRepo is an in-memory Repository. Conversations are keyed by canonical pair; messages are stored
// per conversation. List* return preset slices a test seeds, with the participant guard enforced so the
// authorization behavior of ListMessages can be exercised without SQL.
type fakeRepo struct {
	convs     map[string]Conversation
	messages  map[uuid.UUID][]Message
	insertErr error
}

func newFakeRepo() *fakeRepo {
	return &fakeRepo{convs: map[string]Conversation{}, messages: map[uuid.UUID][]Message{}}
}

func pairKey(a, b uuid.UUID) string {
	lo, hi := orderPair(a, b)
	return lo.String() + "|" + hi.String()
}

func (r *fakeRepo) GetOrCreateConversation(_ context.Context, a, b uuid.UUID) (Conversation, error) {
	key := pairKey(a, b)
	if c, ok := r.convs[key]; ok {
		return c, nil
	}
	lo, hi := orderPair(a, b)
	c := Conversation{ID: uuid.New(), UserLo: lo, UserHi: hi, CreatedAt: time.Now(), LastMessageAt: time.Now()}
	r.convs[key] = c
	return c, nil
}

func (r *fakeRepo) InsertMessage(_ context.Context, m Message) (Message, error) {
	if r.insertErr != nil {
		return Message{}, r.insertErr
	}
	m.ID = uuid.New()
	m.CreatedAt = time.Now()
	r.messages[m.ConversationID] = append(r.messages[m.ConversationID], m)
	return m, nil
}

func (r *fakeRepo) ListConversations(_ context.Context, userID uuid.UUID, _ Cursor, _ bool, limit int) ([]Conversation, error) {
	var out []Conversation
	for _, c := range r.convs {
		if c.hasParticipant(userID) {
			out = append(out, c)
		}
		if len(out) == limit {
			break
		}
	}
	return out, nil
}

func (r *fakeRepo) ListMessages(_ context.Context, conversationID, userID uuid.UUID, _ Cursor, _ bool, limit int) ([]Message, error) {
	// Enforce the participant guard the SQL join provides: a non-participant sees nothing.
	var participant bool
	for _, c := range r.convs {
		if c.ID == conversationID && c.hasParticipant(userID) {
			participant = true
		}
	}
	if !participant {
		return nil, nil
	}
	msgs := r.messages[conversationID]
	if len(msgs) > limit {
		msgs = msgs[:limit]
	}
	return msgs, nil
}

// fakePublisher records every (userID, message) fan-out so a test can assert both participants were
// published to.
type fakePublisher struct {
	calls []struct {
		user uuid.UUID
		msg  Message
	}
}

func (p *fakePublisher) Publish(userID uuid.UUID, m Message) {
	p.calls = append(p.calls, struct {
		user uuid.UUID
		msg  Message
	}{userID, m})
}

func newService(repo Repository, pub Publisher) *MessagingService {
	cfg := Config{DefaultLimit: 30, MaxLimit: 100, MaxMessageBytes: 4000}
	return NewMessagingService(cfg, repo, pub, testLogger())
}

func TestSendMessageRejectsSelf(t *testing.T) {
	u := uuid.New()
	svc := newService(newFakeRepo(), &fakePublisher{})
	if _, err := svc.SendMessage(context.Background(), u, u, "hi"); !errors.Is(err, errSelfMessage) {
		t.Fatalf("want errSelfMessage, got %v", err)
	}
}

func TestSendMessageRejectsEmptyBody(t *testing.T) {
	svc := newService(newFakeRepo(), &fakePublisher{})
	if _, err := svc.SendMessage(context.Background(), uuid.New(), uuid.New(), "   "); !errors.Is(err, errEmptyBody) {
		t.Fatalf("want errEmptyBody, got %v", err)
	}
}

func TestSendMessageRejectsTooLong(t *testing.T) {
	svc := newService(newFakeRepo(), &fakePublisher{})
	long := strings.Repeat("x", 4001)
	if _, err := svc.SendMessage(context.Background(), uuid.New(), uuid.New(), long); !errors.Is(err, errBodyTooLong) {
		t.Fatalf("want errBodyTooLong, got %v", err)
	}
}

func TestSendMessagePersistsAndFansOutToBoth(t *testing.T) {
	repo := newFakeRepo()
	pub := &fakePublisher{}
	svc := newService(repo, pub)
	sender, recipient := uuid.New(), uuid.New()

	msg, err := svc.SendMessage(context.Background(), sender, recipient, "  hello  ")
	if err != nil {
		t.Fatalf("send: %v", err)
	}
	if msg.ID == uuid.Nil || msg.Body != "hello" {
		t.Fatalf("stored message wrong (body should be trimmed): %+v", msg)
	}
	if msg.SenderID != sender || msg.RecipientID != recipient {
		t.Fatalf("addressing wrong: %+v", msg)
	}
	if len(repo.messages[msg.ConversationID]) != 1 {
		t.Fatalf("expected one persisted message, got %d", len(repo.messages[msg.ConversationID]))
	}
	// Published to recipient first, then sender (multi-device echo).
	if len(pub.calls) != 2 {
		t.Fatalf("expected 2 publishes (recipient + sender), got %d", len(pub.calls))
	}
	if pub.calls[0].user != recipient || pub.calls[1].user != sender {
		t.Fatalf("fan-out order wrong: %v then %v", pub.calls[0].user, pub.calls[1].user)
	}
}

func TestSendMessageReusesConversation(t *testing.T) {
	repo := newFakeRepo()
	svc := newService(repo, &fakePublisher{})
	a, b := uuid.New(), uuid.New()

	m1, _ := svc.SendMessage(context.Background(), a, b, "first")
	m2, _ := svc.SendMessage(context.Background(), b, a, "reply") // reversed direction, same pair
	if m1.ConversationID != m2.ConversationID {
		t.Fatalf("same pair must reuse one conversation: %v vs %v", m1.ConversationID, m2.ConversationID)
	}
	if len(repo.convs) != 1 {
		t.Fatalf("expected exactly one conversation, got %d", len(repo.convs))
	}
}

func TestListMessagesIsEmptyForNonParticipant(t *testing.T) {
	repo := newFakeRepo()
	svc := newService(repo, &fakePublisher{})
	a, b := uuid.New(), uuid.New()
	m, _ := svc.SendMessage(context.Background(), a, b, "private")

	intruder := uuid.New()
	page, err := svc.ListMessages(context.Background(), m.ConversationID, intruder, "", 0)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(page.Items) != 0 {
		t.Fatalf("non-participant must see no messages, got %d", len(page.Items))
	}
}

func TestListMessagesInvalidCursor(t *testing.T) {
	svc := newService(newFakeRepo(), &fakePublisher{})
	if _, err := svc.ListMessages(context.Background(), uuid.New(), uuid.New(), "!!bad!!", 0); !errors.Is(err, errInvalidCursor) {
		t.Fatalf("want errInvalidCursor, got %v", err)
	}
}

func TestListConversationsSetsNextCursorOnFullPage(t *testing.T) {
	repo := newFakeRepo()
	svc := newService(repo, &fakePublisher{})
	me := uuid.New()
	// Seed three conversations for me, then request a full page of 3 → a next cursor is emitted.
	for i := 0; i < 3; i++ {
		svc.SendMessage(context.Background(), me, uuid.New(), "hi")
	}
	page, err := svc.ListConversations(context.Background(), me, "", 3)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(page.Items) != 3 {
		t.Fatalf("expected 3 conversations, got %d", len(page.Items))
	}
	if page.NextCursor == "" {
		t.Fatal("a full page should carry a NextCursor")
	}
	for _, v := range page.Items {
		if v.OtherUserID == me {
			t.Fatalf("view should name the counterpart, not the caller: %+v", v)
		}
	}
}
