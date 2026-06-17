package main

import (
	"sync"

	"github.com/google/uuid"
)

// Hub is the in-process SSE fan-out. Each connected stream subscribes with the recipient's user ID
// and receives notifications addressed to them.
//
// Delivery is best-effort: every notification is PERSISTED before publish, so a message dropped here
// is still retrievable via GET /api/v1/notifications. One consequence of the in-process design is
// that a replica only reaches the clients connected to ITSELF — acceptable for Slice 1 (sticky SSE
// connections); a later slice swaps this for Redis pub/sub so any replica reaches any client.
type Hub struct {
	mu   sync.RWMutex
	subs map[uuid.UUID]map[chan Notification]struct{}
}

func NewHub() *Hub {
	return &Hub{subs: make(map[uuid.UUID]map[chan Notification]struct{})}
}

// Subscribe registers a new SSE stream for userID and returns its receive channel plus an
// unsubscribe function the handler must defer-call when the connection closes. unsubscribe is
// idempotent.
func (h *Hub) Subscribe(userID uuid.UUID) (<-chan Notification, func()) {
	ch := make(chan Notification, 16)

	h.mu.Lock()
	if h.subs[userID] == nil {
		h.subs[userID] = make(map[chan Notification]struct{})
	}
	h.subs[userID][ch] = struct{}{}
	h.mu.Unlock()

	var once sync.Once
	unsubscribe := func() {
		once.Do(func() {
			h.mu.Lock()
			if set := h.subs[userID]; set != nil {
				delete(set, ch)
				if len(set) == 0 {
					delete(h.subs, userID)
				}
			}
			h.mu.Unlock()
			close(ch)
		})
	}
	return ch, unsubscribe
}

// Publish delivers n to every stream subscribed by its recipient. A full subscriber buffer is
// skipped (non-blocking send) so a slow client never stalls the Kafka consumer — the dropped event
// remains in Postgres for the client to fetch on reconnect. unsubscribe takes the write lock, so a
// channel can never be closed mid-send while Publish holds the read lock.
func (h *Hub) Publish(recipientID uuid.UUID, n Notification) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for ch := range h.subs[recipientID] {
		select {
		case ch <- n:
		default:
		}
	}
}

// SubscriberCount reports how many streams are currently connected for userID (test/metrics helper).
func (h *Hub) SubscriberCount(userID uuid.UUID) int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.subs[userID])
}
