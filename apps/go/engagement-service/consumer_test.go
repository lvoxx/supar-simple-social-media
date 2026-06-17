package main

import (
	"context"
	"testing"

	"github.com/lvoxx/sssm/go/common/eventv1"
	"google.golang.org/protobuf/encoding/protowire"
)

type fakeSink struct {
	engagement int
	deleted    int
}

func (f *fakeSink) OnPostEngagement(context.Context, eventv1.PostEngagement) error {
	f.engagement++
	return nil
}
func (f *fakeSink) OnPostDeleted(context.Context, eventv1.PostDeleted) error { f.deleted++; return nil }

func newConsumer(sink EventSink) *Consumer { return NewConsumer(nil, sink, discardLogger()) }

// minimal valid message body (field 1 = post_id string) decodable by either event decoder.
func postIDBytes() []byte {
	var b []byte
	b = protowire.AppendTag(b, 1, protowire.BytesType)
	return protowire.AppendString(b, "post-1")
}

func TestRouteDispatchesByEventType(t *testing.T) {
	sink := &fakeSink{}
	c := newConsumer(sink)
	if err := c.route(context.Background(), typePostEngagement, postIDBytes()); err != nil {
		t.Fatalf("engagement route: %v", err)
	}
	if err := c.route(context.Background(), typePostDeleted, postIDBytes()); err != nil {
		t.Fatalf("deleted route: %v", err)
	}
	if sink.engagement != 1 || sink.deleted != 1 {
		t.Errorf("wrong dispatch: %+v", sink)
	}
}

func TestRouteIgnoresUnknownEventType(t *testing.T) {
	sink := &fakeSink{}
	c := newConsumer(sink)
	if err := c.route(context.Background(), "PostCreated", postIDBytes()); err != nil {
		t.Fatalf("unknown type must be a no-op: %v", err)
	}
	if sink.engagement+sink.deleted != 0 {
		t.Error("unknown event type must not invoke any handler")
	}
}

func TestRouteDropsPoisonPayload(t *testing.T) {
	sink := &fakeSink{}
	c := newConsumer(sink)
	if err := c.route(context.Background(), typePostEngagement, []byte{0xFF}); err != nil {
		t.Errorf("poison payload should be dropped, got: %v", err)
	}
	if sink.engagement != 0 {
		t.Error("poison payload must not reach the sink")
	}
}
