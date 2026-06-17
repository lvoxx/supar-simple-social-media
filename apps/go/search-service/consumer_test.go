package main

import (
	"context"
	"encoding/base64"
	"io"
	"log/slog"
	"testing"

	"github.com/lvoxx/sssm/go/common/eventv1"
)

// encodeBytes is a small helper shared by the service tests for building cursor tokens from raw
// strings.
func encodeBytes(s string) string { return base64.RawURLEncoding.EncodeToString([]byte(s)) }

// recordingSink captures which sink method the consumer routed an event to.
type recordingSink struct {
	created []eventv1.PostCreated
	deleted []eventv1.PostDeleted
}

func (r *recordingSink) OnPostCreated(_ context.Context, e eventv1.PostCreated) error {
	r.created = append(r.created, e)
	return nil
}

func (r *recordingSink) OnPostDeleted(_ context.Context, e eventv1.PostDeleted) error {
	r.deleted = append(r.deleted, e)
	return nil
}

func testConsumer(sink EventSink) *Consumer {
	return NewConsumer(nil, sink, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

// postCreatedBytes/postDeletedBytes build valid protowire message bodies (via appendString in
// index_test.go) so routing is exercised end-to-end through eventv1's decoder.
func postCreatedBytes(postID, authorID, text string) []byte {
	var b []byte
	b = appendString(b, 1, postID)
	b = appendString(b, 2, authorID)
	b = appendString(b, 3, text)
	return b
}

func postDeletedBytes(postID string) []byte {
	return appendString(nil, 1, postID)
}

func TestRouteIndexesPostCreated(t *testing.T) {
	sink := &recordingSink{}
	c := testConsumer(sink)

	if err := c.route(context.Background(), typePostCreated, postCreatedBytes("p1", "a1", "hello")); err != nil {
		t.Fatalf("route: %v", err)
	}
	if len(sink.created) != 1 || sink.created[0].PostID != "p1" || sink.created[0].Text != "hello" {
		t.Fatalf("PostCreated not routed: %+v", sink.created)
	}
}

func TestRouteDeletesPostDeleted(t *testing.T) {
	sink := &recordingSink{}
	c := testConsumer(sink)

	if err := c.route(context.Background(), typePostDeleted, postDeletedBytes("p1")); err != nil {
		t.Fatalf("route: %v", err)
	}
	if len(sink.deleted) != 1 || sink.deleted[0].PostID != "p1" {
		t.Fatalf("PostDeleted not routed: %+v", sink.deleted)
	}
}

func TestRouteIgnoresEngagementAndUnknown(t *testing.T) {
	sink := &recordingSink{}
	c := testConsumer(sink)

	if err := c.route(context.Background(), "PostEngagement", []byte{0x08, 0x01}); err != nil {
		t.Fatalf("route PostEngagement: %v", err)
	}
	if err := c.route(context.Background(), "SomethingElse", nil); err != nil {
		t.Fatalf("route unknown: %v", err)
	}
	if len(sink.created) != 0 || len(sink.deleted) != 0 {
		t.Fatalf("non-content events should be ignored: created=%v deleted=%v", sink.created, sink.deleted)
	}
}

func TestRouteDropsPoisonMessage(t *testing.T) {
	sink := &recordingSink{}
	c := testConsumer(sink)

	// A truncated varint tag is undecodable; route must swallow it (commit/skip) not error.
	if err := c.route(context.Background(), typePostCreated, []byte{0xFF, 0xFF}); err != nil {
		t.Fatalf("poison message should be dropped, got err: %v", err)
	}
	if len(sink.created) != 0 {
		t.Fatal("poison message should not reach the sink")
	}
}
