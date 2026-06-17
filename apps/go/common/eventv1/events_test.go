package eventv1

import (
	"testing"
	"time"

	"google.golang.org/protobuf/encoding/protowire"
)

// appendTimestamp encodes a google.protobuf.Timestamp exactly as protoc would: field 1 seconds
// (varint), field 2 nanos (varint), the whole thing length-delimited as a nested message.
func appendTimestamp(t time.Time) []byte {
	var msg []byte
	msg = protowire.AppendTag(msg, 1, protowire.VarintType)
	msg = protowire.AppendVarint(msg, uint64(t.Unix()))
	msg = protowire.AppendTag(msg, 2, protowire.VarintType)
	msg = protowire.AppendVarint(msg, uint64(t.Nanosecond()))
	return msg
}

func appendString(b []byte, num protowire.Number, s string) []byte {
	b = protowire.AppendTag(b, num, protowire.BytesType)
	return protowire.AppendString(b, s)
}

func appendMessage(b []byte, num protowire.Number, msg []byte) []byte {
	b = protowire.AppendTag(b, num, protowire.BytesType)
	return protowire.AppendBytes(b, msg)
}

func TestDecodePostCreatedRoundTrip(t *testing.T) {
	created := time.Date(2026, 6, 17, 10, 30, 0, 123456789, time.UTC)
	var b []byte
	b = appendString(b, 1, "post-1")
	b = appendString(b, 2, "author-1")
	b = appendString(b, 3, "hello world")
	b = appendString(b, 4, "media-a")
	b = appendString(b, 4, "media-b") // repeated
	b = appendString(b, 5, "parent-9")
	b = appendMessage(b, 6, appendTimestamp(created))

	got, err := DecodePostCreated(b)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if got.PostID != "post-1" || got.AuthorID != "author-1" || got.Text != "hello world" {
		t.Errorf("scalar fields wrong: %+v", got)
	}
	if got.ReplyToPostID != "parent-9" {
		t.Errorf("ReplyToPostID = %q, want parent-9", got.ReplyToPostID)
	}
	if len(got.MediaIDs) != 2 || got.MediaIDs[0] != "media-a" || got.MediaIDs[1] != "media-b" {
		t.Errorf("MediaIDs = %v, want [media-a media-b]", got.MediaIDs)
	}
	if !got.CreatedAt.Equal(created) {
		t.Errorf("CreatedAt = %v, want %v", got.CreatedAt, created)
	}
}

func TestDecodePostCreatedTopLevelHasEmptyReplyTo(t *testing.T) {
	var b []byte
	b = appendString(b, 1, "post-1")
	b = appendString(b, 2, "author-1")
	// no field 5 -> top-level post
	got, err := DecodePostCreated(b)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if got.ReplyToPostID != "" {
		t.Errorf("top-level post should have empty ReplyToPostID, got %q", got.ReplyToPostID)
	}
}

func TestDecodePostEngagementRoundTrip(t *testing.T) {
	occurred := time.Date(2026, 6, 17, 11, 0, 0, 0, time.UTC)
	var b []byte
	b = appendString(b, 1, "post-7")
	b = appendString(b, 2, "actor-3")
	b = protowire.AppendTag(b, 3, protowire.VarintType)
	b = protowire.AppendVarint(b, uint64(EngagementRepost))
	b = appendMessage(b, 4, appendTimestamp(occurred))

	got, err := DecodePostEngagement(b)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if got.PostID != "post-7" || got.ActorID != "actor-3" {
		t.Errorf("ids wrong: %+v", got)
	}
	if got.Type != EngagementRepost {
		t.Errorf("Type = %d, want %d (REPOST)", got.Type, EngagementRepost)
	}
	if !got.OccurredAt.Equal(occurred) {
		t.Errorf("OccurredAt = %v, want %v", got.OccurredAt, occurred)
	}
}

func TestDecodePostDeletedRoundTrip(t *testing.T) {
	deleted := time.Date(2026, 6, 17, 12, 0, 0, 0, time.UTC)
	var b []byte
	b = appendString(b, 1, "post-9")
	b = appendString(b, 2, "author-2")
	b = appendMessage(b, 3, appendTimestamp(deleted))

	got, err := DecodePostDeleted(b)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if got.PostID != "post-9" || got.AuthorID != "author-2" {
		t.Errorf("ids wrong: %+v", got)
	}
	if !got.DeletedAt.Equal(deleted) {
		t.Errorf("DeletedAt = %v, want %v", got.DeletedAt, deleted)
	}
}

// Unknown/forward-compatible fields on the producer side must be skipped, not error.
func TestDecodeIgnoresUnknownFields(t *testing.T) {
	var b []byte
	b = appendString(b, 1, "post-1")
	// future field 99, varint wire type
	b = protowire.AppendTag(b, 99, protowire.VarintType)
	b = protowire.AppendVarint(b, 42)
	// future field 100, fixed64 wire type (must be skipped wholesale)
	b = protowire.AppendTag(b, 100, protowire.Fixed64Type)
	b = protowire.AppendFixed64(b, 7)
	b = appendString(b, 2, "author-1")

	got, err := DecodePostCreated(b)
	if err != nil {
		t.Fatalf("decode should ignore unknown fields, got error: %v", err)
	}
	if got.PostID != "post-1" || got.AuthorID != "author-1" {
		t.Errorf("known fields after unknowns decoded wrong: %+v", got)
	}
}

func TestDecodeUnknownEngagementTypePreserved(t *testing.T) {
	var b []byte
	b = protowire.AppendTag(b, 3, protowire.VarintType)
	b = protowire.AppendVarint(b, 999) // enum number not in our known set
	got, err := DecodePostEngagement(b)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if got.Type != EngagementType(999) {
		t.Errorf("open enum should preserve raw value, got %d", got.Type)
	}
}
