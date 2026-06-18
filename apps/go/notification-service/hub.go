package main

import (
	"sync"

	"github.com/google/uuid"
)

// localRegistry is the per-replica SSE subscriber set: it maps a recipient user to the channels of
// the streams currently connected to THIS replica. RedisHub wraps it to add cross-replica fan-out —
// the registry itself only ever delivers to connections on the local process.
type localRegistry struct {
	mu   sync.RWMutex
	subs map[uuid.UUID]map[chan Notification]struct{}
}

func newLocalRegistry() *localRegistry {
	return &localRegistry{subs: make(map[uuid.UUID]map[chan Notification]struct{})}
}

// add registers a new stream channel for userID. first reports that this is the only stream for that
// user on this replica — the caller then SUBSCRIBEs the user's Redis channel so the replica starts
// receiving their notifications. remove deletes the channel (idempotently) and returns last == true
// when it was the user's final stream here, at which point the caller UNSUBSCRIBEs the Redis channel.
func (r *localRegistry) add(userID uuid.UUID) (ch chan Notification, first bool, remove func() (last bool)) {
	c := make(chan Notification, 16)

	r.mu.Lock()
	if r.subs[userID] == nil {
		r.subs[userID] = make(map[chan Notification]struct{})
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

// deliver sends n to every local stream subscribed by recipientID. A full subscriber buffer is
// skipped (non-blocking send) so a slow client never stalls the pub/sub receive loop — the dropped
// event remains in Postgres for the client to fetch on reconnect. remove takes the write lock, so a
// channel can never be closed mid-send while deliver holds the read lock.
func (r *localRegistry) deliver(recipientID uuid.UUID, n Notification) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for ch := range r.subs[recipientID] {
		select {
		case ch <- n:
		default:
		}
	}
}

// count reports how many local streams are currently connected for userID (test/metrics helper).
func (r *localRegistry) count(userID uuid.UUID) int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.subs[userID])
}
