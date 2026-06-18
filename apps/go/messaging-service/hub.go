package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"
	"sync"

	"github.com/google/uuid"
)

// localRegistry is the per-replica WebSocket session set: it maps a user to the channels of the
// sessions currently connected to THIS replica. RedisHub wraps it to add cross-replica fan-out — the
// registry itself only ever delivers to connections on the local process.
type localRegistry struct {
	mu   sync.RWMutex
	subs map[uuid.UUID]map[chan Message]struct{}
}

func newLocalRegistry() *localRegistry {
	return &localRegistry{subs: make(map[uuid.UUID]map[chan Message]struct{})}
}

// add registers a new session channel for userID. first reports that this is the only session for that
// user on this replica — the caller then SUBSCRIBEs the user's Redis channel. remove deletes the
// channel (idempotently) and returns last == true when it was the user's final session here, at which
// point the caller UNSUBSCRIBEs.
func (r *localRegistry) add(userID uuid.UUID) (ch chan Message, first bool, remove func() (last bool)) {
	c := make(chan Message, 32)

	r.mu.Lock()
	if r.subs[userID] == nil {
		r.subs[userID] = make(map[chan Message]struct{})
	}
	r.subs[userID][c] = struct{}{}
	first = len(r.subs[userID]) == 1
	r.mu.Unlock()

	var once sync.Once
	remove = func() (last bool) {
		once.Do(func() {
			r.mu.Lock()
			if set := r.subs[userID]; set != nil {
				delete(set, c)
				if len(set) == 0 {
					delete(r.subs, userID)
					last = true
				}
			}
			r.mu.Unlock()
			close(c)
		})
		return last
	}
	return c, first, remove
}

// deliver sends m to every local session subscribed by userID. A full session buffer is skipped
// (non-blocking send) so a slow client never stalls the pub/sub receive loop — the dropped message
// remains in Postgres for the client to fetch on reconnect. remove takes the write lock, so a channel
// can never be closed mid-send while deliver holds the read lock.
func (r *localRegistry) deliver(userID uuid.UUID, m Message) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for ch := range r.subs[userID] {
		select {
		case ch <- m:
		default:
		}
	}
}

func (r *localRegistry) count(userID uuid.UUID) int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.subs[userID])
}

// RedisHub is the cross-replica WebSocket fan-out. It keeps a per-replica registry of connected
// sessions and bridges replicas over a Redis pub/sub channel per user: Publish broadcasts a message to
// a user's channel, and every replica that has that user connected receives it (via Run) and delivers
// to its local sessions. So a DM reaches the recipient no matter which replica their socket landed on.
//
// Delivery stays best-effort: every message is PERSISTED before Publish, so anything dropped here (full
// client buffer, Redis outage) is still retrievable via the message history API. A Redis error is
// therefore logged and swallowed rather than failing the send.
type RedisHub struct {
	reg       *localRegistry
	transport PubSub
	log       *slog.Logger
}

func NewRedisHub(transport PubSub, log *slog.Logger) *RedisHub {
	return &RedisHub{reg: newLocalRegistry(), transport: transport, log: log}
}

// Subscribe registers a new WebSocket session for userID and returns its receive channel plus an
// idempotent unsubscribe the session must defer-call when the connection closes. The first session for
// a user on this replica SUBSCRIBEs the user's Redis channel; the last one to leave UNSUBSCRIBEs it.
func (h *RedisHub) Subscribe(userID uuid.UUID) (<-chan Message, func()) {
	ch, first, remove := h.reg.add(userID)
	if first {
		if err := h.transport.Subscribe(context.Background(), channelFor(userID)); err != nil {
			h.log.Error("redis subscribe failed; cross-replica delivery degraded", "user_id", userID, "err", err)
		}
	}
	unsubscribe := func() {
		if last := remove(); last {
			if err := h.transport.Unsubscribe(context.Background(), channelFor(userID)); err != nil {
				h.log.Error("redis unsubscribe failed", "user_id", userID, "err", err)
			}
		}
	}
	return ch, unsubscribe
}

// Publish broadcasts m to userID's Redis channel so every replica serving that user delivers it to
// their live sessions. The destination user is carried by the channel name, so the receiving side
// routes by channel, never by payload — a malformed payload can never misroute a DM.
func (h *RedisHub) Publish(userID uuid.UUID, m Message) {
	payload, err := json.Marshal(m)
	if err != nil {
		h.log.Error("marshal message for pub/sub failed", "err", err)
		return
	}
	if err := h.transport.Publish(context.Background(), channelFor(userID), payload); err != nil {
		h.log.Error("redis publish failed; live delivery skipped", "user_id", userID, "err", err)
	}
}

// Run consumes cross-replica messages until ctx is cancelled or the transport closes, delivering each
// to the local sessions of its destination user. The user is parsed from the channel name (not the
// payload), so a malformed payload can never misroute a message to the wrong user.
func (h *RedisHub) Run(ctx context.Context) {
	msgs := h.transport.Messages()
	for {
		select {
		case <-ctx.Done():
			return
		case msg, ok := <-msgs:
			if !ok {
				return
			}
			userID, err := uuid.Parse(strings.TrimPrefix(msg.Channel, channelPrefix))
			if err != nil {
				h.log.Warn("pub/sub message on unrecognized channel", "channel", msg.Channel)
				continue
			}
			var m Message
			if err := json.Unmarshal(msg.Payload, &m); err != nil {
				h.log.Warn("decode pub/sub message failed", "channel", msg.Channel, "err", err)
				continue
			}
			h.reg.deliver(userID, m)
		}
	}
}

// SubscriberCount reports how many local sessions are connected for userID (test/metrics helper).
func (h *RedisHub) SubscriberCount(userID uuid.UUID) int { return h.reg.count(userID) }
