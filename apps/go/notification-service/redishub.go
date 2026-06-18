package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"

	"github.com/google/uuid"
)

// RedisHub is the cross-replica SSE fan-out. It keeps a per-replica registry of connected streams and
// bridges replicas over a Redis pub/sub channel per recipient: Publish broadcasts a notification to
// the recipient's channel, and every replica that has that recipient connected receives it (via Run)
// and delivers to its local streams. This replaces Slice 1's in-process Hub, so a notification reaches
// the recipient no matter which replica their SSE connection landed on.
//
// Delivery stays best-effort: every notification is PERSISTED before Publish, so anything dropped here
// (full client buffer, Redis outage) is still retrievable via GET /api/v1/notifications. A Redis error
// is therefore logged and swallowed rather than failing the request or wedging the Kafka consumer.
type RedisHub struct {
	reg       *localRegistry
	transport PubSub
	log       *slog.Logger
}

func NewRedisHub(transport PubSub, log *slog.Logger) *RedisHub {
	return &RedisHub{reg: newLocalRegistry(), transport: transport, log: log}
}

// Subscribe registers a new SSE stream for userID and returns its receive channel plus an idempotent
// unsubscribe the handler must defer-call when the connection closes. The first stream for a user on
// this replica SUBSCRIBEs the user's Redis channel; the last one to leave UNSUBSCRIBEs it, so a
// replica only listens for the users it is actually serving.
func (h *RedisHub) Subscribe(userID uuid.UUID) (<-chan Notification, func()) {
	ch, first, remove := h.reg.add(userID)
	if first {
		if err := h.transport.Subscribe(context.Background(), channelFor(userID)); err != nil {
			// The local stream still works for notifications published by THIS replica; cross-replica
			// delivery is what degrades. The persisted inbox remains the backstop.
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

// Publish broadcasts n to the recipient's Redis channel so every replica serving that recipient
// delivers it to their live streams. Implements Publisher. RecipientID is carried by the channel name
// (the field is json:"-"), so the receiving side routes by channel, never by payload.
func (h *RedisHub) Publish(recipientID uuid.UUID, n Notification) {
	payload, err := json.Marshal(n)
	if err != nil {
		h.log.Error("marshal notification for pub/sub failed", "err", err)
		return
	}
	if err := h.transport.Publish(context.Background(), channelFor(recipientID), payload); err != nil {
		h.log.Error("redis publish failed; live delivery skipped", "recipient_id", recipientID, "err", err)
	}
}

// Run consumes cross-replica messages until ctx is cancelled or the transport closes, delivering each
// to the local streams of its recipient. The recipient is parsed from the channel name (not the
// payload), so a malformed payload can never misroute a notification to the wrong user.
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
			recipientID, err := uuid.Parse(strings.TrimPrefix(msg.Channel, channelPrefix))
			if err != nil {
				h.log.Warn("pub/sub message on unrecognized channel", "channel", msg.Channel)
				continue
			}
			var n Notification
			if err := json.Unmarshal(msg.Payload, &n); err != nil {
				h.log.Warn("decode pub/sub notification failed", "channel", msg.Channel, "err", err)
				continue
			}
			h.reg.deliver(recipientID, n)
		}
	}
}

// SubscriberCount reports how many local streams are connected for userID (test/metrics helper).
func (h *RedisHub) SubscriberCount(userID uuid.UUID) int { return h.reg.count(userID) }
