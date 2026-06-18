package main

import (
	"context"
	"encoding/json"
	"io"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
)

// fakeConn is an in-memory WSConn. Tests push inbound frames onto reads (closing it ends the read
// pump) and read back what the session wrote.
type fakeConn struct {
	reads  chan []byte
	mu     sync.Mutex
	writes [][]byte
	closed bool
}

func newFakeConn() *fakeConn { return &fakeConn{reads: make(chan []byte)} }

func (c *fakeConn) Read(ctx context.Context) ([]byte, error) {
	select {
	case b, ok := <-c.reads:
		if !ok {
			return nil, io.EOF
		}
		return b, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (c *fakeConn) Write(_ context.Context, data []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	cp := make([]byte, len(data))
	copy(cp, data)
	c.writes = append(c.writes, cp)
	return nil
}

func (c *fakeConn) Ping(context.Context) error { return nil }

func (c *fakeConn) Close(string) {
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
}

func (c *fakeConn) written() []serverFrame {
	c.mu.Lock()
	defer c.mu.Unlock()
	frames := make([]serverFrame, 0, len(c.writes))
	for _, w := range c.writes {
		var f serverFrame
		if json.Unmarshal(w, &f) == nil {
			frames = append(frames, f)
		}
	}
	return frames
}

type sendCall struct {
	sender, recipient uuid.UUID
	body              string
}

// fakeSender records SendMessage calls and can be made to return a preset error.
type fakeSender struct {
	mu    sync.Mutex
	calls []sendCall
	err   error
}

func (s *fakeSender) SendMessage(_ context.Context, sender, recipient uuid.UUID, body string) (Message, error) {
	s.mu.Lock()
	s.calls = append(s.calls, sendCall{sender, recipient, body})
	s.mu.Unlock()
	if s.err != nil {
		return Message{}, s.err
	}
	return Message{ID: uuid.New(), SenderID: sender, RecipientID: recipient, Body: body}, nil
}

func (s *fakeSender) recorded() []sendCall {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]sendCall(nil), s.calls...)
}

// fakeSessionHub hands out a channel the test controls so it can drive the write pump directly.
type fakeSessionHub struct{ ch chan Message }

func (h *fakeSessionHub) Subscribe(uuid.UUID) (<-chan Message, func()) { return h.ch, func() {} }

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return b
}

func waitForFrames(t *testing.T, conn *fakeConn, n int) []serverFrame {
	t.Helper()
	deadline := time.After(time.Second)
	for {
		if f := conn.written(); len(f) >= n {
			return f
		}
		select {
		case <-deadline:
			t.Fatalf("timed out waiting for %d frame(s), got %d", n, len(conn.written()))
		case <-time.After(5 * time.Millisecond):
		}
	}
}

func TestSessionReadForwardsSend(t *testing.T) {
	conn := newFakeConn()
	sender := &fakeSender{}
	hub := &fakeSessionHub{ch: make(chan Message)}
	sess := NewSession(sender, hub, testLogger())
	user, recipient := uuid.New(), uuid.New()

	done := make(chan struct{})
	go func() { sess.Serve(context.Background(), user, conn); close(done) }()

	conn.reads <- mustJSON(t, clientFrame{RecipientID: recipient.String(), Body: "hi"})
	close(conn.reads) // ends the read pump → Serve returns
	<-done

	calls := sender.recorded()
	if len(calls) != 1 {
		t.Fatalf("expected one send, got %d", len(calls))
	}
	if calls[0].sender != user || calls[0].recipient != recipient || calls[0].body != "hi" {
		t.Fatalf("forwarded wrong args: %+v", calls[0])
	}
	if !conn.closed {
		t.Fatal("connection should be closed after Serve returns")
	}
}

func TestSessionWriteDeliversHubMessage(t *testing.T) {
	conn := newFakeConn()
	hub := &fakeSessionHub{ch: make(chan Message, 1)}
	sess := NewSession(&fakeSender{}, hub, testLogger())

	done := make(chan struct{})
	go func() { sess.Serve(context.Background(), uuid.New(), conn); close(done) }()

	msg := Message{ID: uuid.New(), Body: "live"}
	hub.ch <- msg

	frames := waitForFrames(t, conn, 1)
	if frames[0].Type != "message" || frames[0].Message == nil || frames[0].Message.ID != msg.ID {
		t.Fatalf("expected a message frame for %v, got %+v", msg.ID, frames[0])
	}

	close(conn.reads)
	<-done
}

func TestSessionWritesErrorOnMalformedFrame(t *testing.T) {
	conn := newFakeConn()
	sender := &fakeSender{}
	sess := NewSession(sender, &fakeSessionHub{ch: make(chan Message)}, testLogger())

	done := make(chan struct{})
	go func() { sess.Serve(context.Background(), uuid.New(), conn); close(done) }()

	conn.reads <- []byte("not json at all")
	frames := waitForFrames(t, conn, 1)
	if frames[0].Type != "error" || frames[0].Error == "" {
		t.Fatalf("expected an error frame, got %+v", frames[0])
	}
	if len(sender.recorded()) != 0 {
		t.Fatal("a malformed frame must not reach SendMessage")
	}

	close(conn.reads)
	<-done
}

func TestSessionWritesErrorOnInvalidRecipient(t *testing.T) {
	conn := newFakeConn()
	sess := NewSession(&fakeSender{}, &fakeSessionHub{ch: make(chan Message)}, testLogger())

	done := make(chan struct{})
	go func() { sess.Serve(context.Background(), uuid.New(), conn); close(done) }()

	conn.reads <- mustJSON(t, clientFrame{RecipientID: "not-a-uuid", Body: "hi"})
	frames := waitForFrames(t, conn, 1)
	if frames[0].Type != "error" {
		t.Fatalf("expected an error frame for a bad recipient id, got %+v", frames[0])
	}

	close(conn.reads)
	<-done
}

func TestSessionSurfacesSendValidationError(t *testing.T) {
	conn := newFakeConn()
	sender := &fakeSender{err: errSelfMessage}
	sess := NewSession(sender, &fakeSessionHub{ch: make(chan Message)}, testLogger())

	done := make(chan struct{})
	go func() { sess.Serve(context.Background(), uuid.New(), conn); close(done) }()

	conn.reads <- mustJSON(t, clientFrame{RecipientID: uuid.New().String(), Body: "hi"})
	frames := waitForFrames(t, conn, 1)
	if frames[0].Type != "error" || frames[0].Error != "cannot send a message to yourself" {
		t.Fatalf("validation error should be surfaced verbatim, got %+v", frames[0])
	}

	close(conn.reads)
	<-done
}
