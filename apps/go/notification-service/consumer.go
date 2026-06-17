package main

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/lvoxx/sssm/go/common/eventv1"
	"github.com/segmentio/kafka-go"
)

// event-type header (set by post-service's OutboxRelay) and its values. The header — not the
// payload — tells us which message the bytes decode to, exactly as the Java producer stamps it.
const eventTypeHeader = "event-type"

const (
	typePostCreated    = "PostCreated"
	typePostDeleted    = "PostDeleted"
	typePostEngagement = "PostEngagement"
)

// EventSink is the slice of NotificationService the consumer drives. An interface so routing is
// unit-testable without a broker or a database.
type EventSink interface {
	OnPostCreated(ctx context.Context, e eventv1.PostCreated) error
	OnPostEngagement(ctx context.Context, e eventv1.PostEngagement) error
	OnPostDeleted(ctx context.Context, e eventv1.PostDeleted) error
}

// Consumer drains the post-events topic into the notification service. Delivery is at-least-once:
// the offset is committed ONLY after the sink reports success, so a downstream (DB) failure leaves
// the message to be redelivered. The sink dedupes redelivery by the notification's natural key.
type Consumer struct {
	reader *kafka.Reader
	sink   EventSink
	log    *slog.Logger
}

func NewConsumer(reader *kafka.Reader, sink EventSink, log *slog.Logger) *Consumer {
	return &Consumer{reader: reader, sink: sink, log: log}
}

// Run consumes until ctx is cancelled. FetchMessage does not auto-commit, so handling failures don't
// advance the committed offset; on success CommitMessages acknowledges the message.
func (c *Consumer) Run(ctx context.Context) error {
	for {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return nil // graceful shutdown
			}
			c.log.Error("kafka fetch failed", "err", err)
			continue
		}

		if err := c.dispatch(ctx, msg); err != nil {
			// Leave the offset uncommitted so the message is redelivered; brief back-off avoids a hot
			// spin while the downstream (e.g. Postgres) is unavailable.
			c.log.Error("event handling failed; will retry on redelivery",
				"err", err, "offset", msg.Offset, "event_type", headerValue(msg.Headers, eventTypeHeader))
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(time.Second):
			}
			continue
		}

		if err := c.reader.CommitMessages(ctx, msg); err != nil {
			c.log.Error("kafka commit failed", "err", err, "offset", msg.Offset)
		}
	}
}

func (c *Consumer) dispatch(ctx context.Context, msg kafka.Message) error {
	return c.route(ctx, headerValue(msg.Headers, eventTypeHeader), msg.Value)
}

// route decodes value according to eventType and hands it to the sink. A decode failure is a poison
// message: it is logged and swallowed (returns nil so the offset commits) rather than blocking the
// partition forever. Unknown event types are ignored — the topic may carry events this service does
// not act on.
func (c *Consumer) route(ctx context.Context, eventType string, value []byte) error {
	switch eventType {
	case typePostCreated:
		e, err := eventv1.DecodePostCreated(value)
		if err != nil {
			c.log.Warn("decode PostCreated failed; dropping", "err", err)
			return nil
		}
		return c.sink.OnPostCreated(ctx, e)
	case typePostEngagement:
		e, err := eventv1.DecodePostEngagement(value)
		if err != nil {
			c.log.Warn("decode PostEngagement failed; dropping", "err", err)
			return nil
		}
		return c.sink.OnPostEngagement(ctx, e)
	case typePostDeleted:
		e, err := eventv1.DecodePostDeleted(value)
		if err != nil {
			c.log.Warn("decode PostDeleted failed; dropping", "err", err)
			return nil
		}
		return c.sink.OnPostDeleted(ctx, e)
	default:
		c.log.Debug("ignoring event type", "event_type", eventType)
		return nil
	}
}

func headerValue(headers []kafka.Header, key string) string {
	for _, h := range headers {
		if h.Key == key {
			return string(h.Value)
		}
	}
	return ""
}
