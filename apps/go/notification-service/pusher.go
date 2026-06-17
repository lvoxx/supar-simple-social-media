package main

import (
	"context"
	"log/slog"

	"github.com/google/uuid"
)

// Pusher delivers a notification to a user's registered devices through an external push provider
// (FCM/APNs). It is an interface so the real provider clients are a drop-in replacement once
// credentials exist.
//
// Slice 1 ships logPusher, a no-op that records intent — the same deferral media-service used for
// Kafka in Phase 1. Device tokens are already collected (POST /api/v1/notifications/devices) so the
// real implementation only needs to read device_tokens and call the provider SDK.
type Pusher interface {
	Push(ctx context.Context, recipientID uuid.UUID, n Notification)
}

type logPusher struct{ log *slog.Logger }

func newLogPusher(log *slog.Logger) *logPusher { return &logPusher{log: log} }

func (p *logPusher) Push(_ context.Context, recipientID uuid.UUID, n Notification) {
	p.log.Info("push notification (stub — no provider wired)",
		"recipient_id", recipientID, "kind", n.Kind, "notification_id", n.ID)
}
