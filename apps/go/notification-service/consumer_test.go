package main

import (
	"context"
	"testing"

	"github.com/lvoxx/sssm/go/common/eventv1"
	"google.golang.org/protobuf/encoding/protowire"
)

// fakeSink records which On* method the router invoked.
type fakeSink struct {
	created    int
	engagement int
	deleted    int
}

func (f *fakeSink) OnPostCreated(context.Context, eventv1.PostCreated) error { f.created++; return nil }
func (f *fakeSink) OnPostEngagement(context.Context, eventv1.PostEngagement) error {
	f.engagement++
	return nil
}
func (f *fakeSink) OnPostDeleted(context.Context, eventv1.PostDeleted) error { f.deleted++; return nil }

func newConsumer(sink EventSink) *Consumer { return NewConsumer(nil, sink, discardLogger()) }

// minimal valid PostCreated body (field 1 = post_id) so decoding succeeds.
func postCreatedBytes() []byte {
	var b []byte
	b = protowire.AppendTag(b, 1, protowire.BytesType)
	return protowire.AppendString(b, "post-1")
}

func TestRouteDispatchesByEventType(t *testing.T) {
	tests := []struct {
		eventType string
		check     func(*fakeSink) bool
	}{
		{typePostCreated, func(s *fakeSink) bool { return s.created == 1 && s.engagement == 0 && s.deleted == 0 }},
		{typePostEngagement, func(s *fakeSink) bool { return s.engagement == 1 && s.created == 0 && s.deleted == 0 }},
		{typePostDeleted, func(s *fakeSink) bool { return s.deleted == 1 && s.created == 0 && s.engagement == 0 }},
	}
	for _, tt := range tests {
		sink := &fakeSink{}
		c := newConsumer(sink)
		if err := c.route(context.Background(), tt.eventType, postCreatedBytes()); err != nil {
			t.Fatalf("%s: unexpected error: %v", tt.eventType, err)
		}
		if !tt.check(sink) {
			t.Errorf("%s routed to the wrong handler: %+v", tt.eventType, sink)
		}
	}
}

func TestRouteIgnoresUnknownEventType(t *testing.T) {
	sink := &fakeSink{}
	c := newConsumer(sink)
	if err := c.route(context.Background(), "FollowCreated", postCreatedBytes()); err != nil {
		t.Fatalf("unknown event type must be a no-op, got: %v", err)
	}
	if sink.created+sink.engagement+sink.deleted != 0 {
		t.Error("unknown event type must not invoke any handler")
	}
}

func TestRouteDropsPoisonPayload(t *testing.T) {
	sink := &fakeSink{}
	c := newConsumer(sink)
	// A truncated varint tag is undecodable; route should swallow it (commit) rather than error.
	if err := c.route(context.Background(), typePostEngagement, []byte{0xFF}); err != nil {
		t.Errorf("poison payload should be dropped, got error: %v", err)
	}
	if sink.engagement != 0 {
		t.Error("poison payload must not reach the sink")
	}
}
