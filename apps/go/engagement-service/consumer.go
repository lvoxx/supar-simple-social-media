package main

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/lvoxx/sssm/go/common/eventv1"
	"github.com/segmentio/kafka-go"
)

// event-type header (set by post-service's OutboxRelay) and the values this service acts on. It
// ignores PostCreated — a new post has no engagement yet.
const eventTypeHeader = "event-type"

const (
	typePostEngagement = "PostEngagement"
	typePostDeleted    = "PostDeleted"
)

// EventSink is the slice of EngagementService the consumer drives. An interface so routing is
// unit-testable without a broker, Redis, or a database.
type EventSink interface {
	OnPostEngagement(ctx context.Context, e eventv1.PostEngagement) error
	OnPostDeleted(ctx context.Context, e eventv1.PostDeleted) error
}

// Consumer drains the post-events topic into the engagement counters. Delivery is at-least-once: the
// offset commits only after the sink succeeds. Counter deltas are NOT idempotent under redelivery,
// but a redelivered like over-counts by at most a small, self-correcting amount — an acceptable
// trade for a ranking signal (exact display counts live on post-service's transactional row).
type Consumer struct {
	reader *kafka.Reader
	sink   EventSink
	log    *slog.Logger
}

func NewConsumer(reader *kafka.Reader, sink EventSink, log *slog.Logger) *Consumer {
	return &Consumer{reader: reader, sink: sink, log: log}
}

func (c *Consumer) Run(ctx context.Context) error {
	for {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return nil
			}
			c.log.Error("kafka fetch failed", "err", err)
			continue
		}

		if err := c.dispatch(ctx, msg); err != nil {
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

// route decodes value according to eventType and hands it to the sink. Decode failures are poison
// messages: logged and swallowed (offset commits) rather than blocking the partition. Unknown types
// (e.g. PostCreated) are ignored.
func (c *Consumer) route(ctx context.Context, eventType string, value []byte) error {
	switch eventType {
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
