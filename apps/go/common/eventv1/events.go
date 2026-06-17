// Package eventv1 decodes the Protobuf domain events that post-service publishes to the
// `sssm.post-events` Kafka topic (schemas/proto/sssm/event/v1/post_events.proto — the single
// source of truth shared with the Java fleet).
//
// The Java side generates its message classes with protoc; the Go fleet has no protoc/buf in the
// local toolchain yet, and timeline-service (the only prior consumer) reads Postgres directly and
// never touches the wire format. notification-service is the FIRST Go consumer, so rather than
// vendoring a generated package this provides a small, dependency-light decoder built on the
// protobuf runtime's low-level `protowire` reader. It only DECODES the handful of fields the
// consumers need; it never produces events. Field numbers and wire types mirror the .proto exactly,
// and events_test.go round-trips real protowire-encoded bytes to guard against drift.
package eventv1

import (
	"time"

	"google.golang.org/protobuf/encoding/protowire"
)

// EngagementType mirrors the proto enum. proto3 enums are open, so unknown numbers decode to their
// raw value rather than failing — consumers switch on the known cases and ignore the rest.
type EngagementType int32

const (
	EngagementUnspecified EngagementType = 0
	EngagementLike        EngagementType = 1
	EngagementUnlike      EngagementType = 2
	EngagementRepost      EngagementType = 3
	EngagementView        EngagementType = 4
	EngagementUnrepost    EngagementType = 5
	EngagementBookmark    EngagementType = 6
	EngagementUnbookmark  EngagementType = 7
)

// PostCreated is emitted when a post (or reply) is created. ReplyToPostID is empty when the post is
// a top-level post rather than a reply.
type PostCreated struct {
	PostID        string
	AuthorID      string
	Text          string
	MediaIDs      []string
	ReplyToPostID string
	CreatedAt     time.Time
}

// PostDeleted is emitted when a post is deleted; consumers purge it from feeds, indexes, and (here)
// the notifications that referenced it.
type PostDeleted struct {
	PostID    string
	AuthorID  string
	DeletedAt time.Time
}

// PostEngagement is emitted on every engagement add/remove (like/unlike, repost/unrepost,
// bookmark/unbookmark). It carries the post and the actor but NOT the post's author, so a consumer
// that needs the recipient must resolve post -> author separately.
type PostEngagement struct {
	PostID     string
	ActorID    string
	Type       EngagementType
	OccurredAt time.Time
}

// DecodePostCreated parses a PostCreated message body.
func DecodePostCreated(b []byte) (PostCreated, error) {
	var p PostCreated
	err := forEachField(b, func(num protowire.Number, val []byte, v uint64) error {
		switch num {
		case 1:
			p.PostID = string(val)
		case 2:
			p.AuthorID = string(val)
		case 3:
			p.Text = string(val)
		case 4:
			p.MediaIDs = append(p.MediaIDs, string(val))
		case 5:
			p.ReplyToPostID = string(val)
		case 6:
			ts, err := decodeTimestamp(val)
			if err != nil {
				return err
			}
			p.CreatedAt = ts
		}
		return nil
	})
	return p, err
}

// DecodePostDeleted parses a PostDeleted message body.
func DecodePostDeleted(b []byte) (PostDeleted, error) {
	var p PostDeleted
	err := forEachField(b, func(num protowire.Number, val []byte, v uint64) error {
		switch num {
		case 1:
			p.PostID = string(val)
		case 2:
			p.AuthorID = string(val)
		case 3:
			ts, err := decodeTimestamp(val)
			if err != nil {
				return err
			}
			p.DeletedAt = ts
		}
		return nil
	})
	return p, err
}

// DecodePostEngagement parses a PostEngagement message body.
func DecodePostEngagement(b []byte) (PostEngagement, error) {
	var p PostEngagement
	err := forEachField(b, func(num protowire.Number, val []byte, v uint64) error {
		switch num {
		case 1:
			p.PostID = string(val)
		case 2:
			p.ActorID = string(val)
		case 3:
			p.Type = EngagementType(int32(v))
		case 4:
			ts, err := decodeTimestamp(val)
			if err != nil {
				return err
			}
			p.OccurredAt = ts
		}
		return nil
	})
	return p, err
}

// decodeTimestamp parses a google.protobuf.Timestamp (field 1 = seconds int64, field 2 = nanos
// int32, both plain varints) into a UTC time.Time. A zero/absent timestamp yields the Unix epoch.
func decodeTimestamp(b []byte) (time.Time, error) {
	var seconds int64
	var nanos int32
	err := forEachField(b, func(num protowire.Number, val []byte, v uint64) error {
		switch num {
		case 1:
			seconds = int64(v)
		case 2:
			nanos = int32(v)
		}
		return nil
	})
	if err != nil {
		return time.Time{}, err
	}
	return time.Unix(seconds, int64(nanos)).UTC(), nil
}

// forEachField walks the top-level fields of a Protobuf message, invoking fn for each. For
// length-delimited (BytesType) fields it passes the raw bytes in val; for varint fields it passes
// the decoded value in v. Other wire types (fixed32/64, groups) are skipped wholesale — none of the
// event schemas use them. Unknown field numbers are consumed and ignored so forward-compatible
// additions on the producer side never break decoding.
func forEachField(b []byte, fn func(num protowire.Number, val []byte, v uint64) error) error {
	for len(b) > 0 {
		num, typ, n := protowire.ConsumeTag(b)
		if n < 0 {
			return protowire.ParseError(n)
		}
		b = b[n:]

		switch typ {
		case protowire.BytesType:
			val, m := protowire.ConsumeBytes(b)
			if m < 0 {
				return protowire.ParseError(m)
			}
			if err := fn(num, val, 0); err != nil {
				return err
			}
			b = b[m:]
		case protowire.VarintType:
			v, m := protowire.ConsumeVarint(b)
			if m < 0 {
				return protowire.ParseError(m)
			}
			if err := fn(num, nil, v); err != nil {
				return err
			}
			b = b[m:]
		default:
			m := protowire.ConsumeFieldValue(num, typ, b)
			if m < 0 {
				return protowire.ParseError(m)
			}
			b = b[m:]
		}
	}
	return nil
}
