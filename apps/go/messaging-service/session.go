package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"
)

// WSConn is the minimal WebSocket surface a Session drives: read a client frame, write a server frame,
// keepalive ping, and close. An interface so the read/write pumps are unit-tested with a fake — the
// concrete coderConn (github.com/coder/websocket) is the only thing that touches a real socket.
type WSConn interface {
	Read(ctx context.Context) ([]byte, error)
	Write(ctx context.Context, data []byte) error
	Ping(ctx context.Context) error
	Close(reason string)
}

// Sender is the slice of MessagingService a Session needs: persist + fan out one DM. *MessagingService
// implements it; tests supply a fake that records calls.
type Sender interface {
	SendMessage(ctx context.Context, senderID, recipientID uuid.UUID, body string) (Message, error)
}

// SessionHub is the slice of the hub a Session subscribes to for outbound delivery. *RedisHub
// implements it.
type SessionHub interface {
	Subscribe(userID uuid.UUID) (<-chan Message, func())
}

// clientFrame is an inbound "send this DM" request from the WebSocket client.
type clientFrame struct {
	RecipientID string `json:"recipientId"`
	Body        string `json:"body"`
}

// serverFrame is an outbound frame to the client: either a delivered message or an error for the
// client's last send. Type discriminates the two.
type serverFrame struct {
	Type    string   `json:"type"`              // "message" | "error"
	Message *Message `json:"message,omitempty"` // set when Type == "message"
	Error   string   `json:"error,omitempty"`   // set when Type == "error"
}

const pingInterval = 25 * time.Second

// Session drives one authenticated WebSocket connection: a read pump turns client frames into sends,
// and a write pump forwards messages the hub delivers for this user (the recipient's live feed plus an
// echo of the user's own sends) out to the socket.
type Session struct {
	svc Sender
	hub SessionHub
	log *slog.Logger
}

func NewSession(svc Sender, hub SessionHub, log *slog.Logger) *Session {
	return &Session{svc: svc, hub: hub, log: log}
}

// Serve runs the connection until the client disconnects or a transport error occurs. It subscribes the
// user to the hub for the lifetime of the socket, runs the write pump in the background, and blocks on
// the read pump; when reads end (client gone), the deferred cancel stops the write pump and the socket
// is closed.
func (s *Session) Serve(ctx context.Context, userID uuid.UUID, conn WSConn) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	ch, unsubscribe := s.hub.Subscribe(userID)
	defer unsubscribe()

	done := make(chan struct{})
	go func() {
		defer close(done)
		s.writeLoop(ctx, conn, ch)
	}()

	s.readLoop(ctx, userID, conn)
	cancel()
	<-done
	conn.Close("bye")
}

// writeLoop forwards hub deliveries to the socket and pings periodically to keep proxies from idling
// the connection out. It returns on ctx cancellation, a closed hub channel, or a write/ping failure.
func (s *Session) writeLoop(ctx context.Context, conn WSConn, ch <-chan Message) {
	ping := time.NewTicker(pingInterval)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case m, ok := <-ch:
			if !ok {
				return
			}
			msg := m
			data, err := json.Marshal(serverFrame{Type: "message", Message: &msg})
			if err != nil {
				s.log.Error("marshal outbound message failed", "err", err)
				continue
			}
			if err := conn.Write(ctx, data); err != nil {
				return
			}
		case <-ping.C:
			if err := conn.Ping(ctx); err != nil {
				return
			}
		}
	}
}

// readLoop reads client send-requests until the connection closes. A malformed frame or a rejected send
// produces an error frame back to the client and the loop continues; a read error (client gone) ends
// it. Successful sends are not acked here — the message is delivered back to this session via the hub
// echo on the sender's own channel.
func (s *Session) readLoop(ctx context.Context, userID uuid.UUID, conn WSConn) {
	for {
		data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		var in clientFrame
		if err := json.Unmarshal(data, &in); err != nil {
			s.writeError(ctx, conn, "invalid message format")
			continue
		}
		recipientID, err := uuid.Parse(in.RecipientID)
		if err != nil {
			s.writeError(ctx, conn, "invalid recipientId")
			continue
		}
		if _, err := s.svc.SendMessage(ctx, userID, recipientID, in.Body); err != nil {
			s.writeError(ctx, conn, s.sendErrorReason(err))
			continue
		}
	}
}

// sendErrorReason maps a SendMessage error to a client-facing reason. Validation errors are surfaced
// verbatim; an unexpected (infrastructure) error is logged and reported generically so internals never
// leak to the client.
func (s *Session) sendErrorReason(err error) string {
	switch {
	case errors.Is(err, errSelfMessage):
		return "cannot send a message to yourself"
	case errors.Is(err, errEmptyBody):
		return "message body must not be empty"
	case errors.Is(err, errBodyTooLong):
		return "message body too long"
	default:
		s.log.Error("send message failed", "err", err)
		return "failed to send message"
	}
}

func (s *Session) writeError(ctx context.Context, conn WSConn, reason string) {
	data, err := json.Marshal(serverFrame{Type: "error", Error: reason})
	if err != nil {
		return
	}
	_ = conn.Write(ctx, data)
}

// coderConn adapts a *github.com/coder/websocket.Conn to WSConn. It is the only place that imports a
// WebSocket library; everything else works against the interface.
type coderConn struct {
	c *websocket.Conn
}

func (cc coderConn) Read(ctx context.Context) ([]byte, error) {
	_, data, err := cc.c.Read(ctx)
	return data, err
}

func (cc coderConn) Write(ctx context.Context, data []byte) error {
	return cc.c.Write(ctx, websocket.MessageText, data)
}

func (cc coderConn) Ping(ctx context.Context) error {
	pctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return cc.c.Ping(pctx)
}

func (cc coderConn) Close(reason string) {
	_ = cc.c.Close(websocket.StatusNormalClosure, reason)
}
