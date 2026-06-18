package main

import (
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestCursorRoundTrip(t *testing.T) {
	want := Cursor{TS: time.Now().UTC().Truncate(time.Nanosecond), ID: uuid.New()}
	got, ok, err := DecodeCursor(want.Encode())
	if err != nil || !ok {
		t.Fatalf("decode: ok=%v err=%v", ok, err)
	}
	if !got.TS.Equal(want.TS) || got.ID != want.ID {
		t.Fatalf("round-trip mismatch: got %+v want %+v", got, want)
	}
}

func TestDecodeCursorEmptyIsFirstPage(t *testing.T) {
	c, ok, err := DecodeCursor("")
	if err != nil {
		t.Fatalf("empty cursor should not error: %v", err)
	}
	if ok {
		t.Fatalf("empty cursor should report ok=false (first page)")
	}
	if !c.TS.IsZero() || c.ID != uuid.Nil {
		t.Fatalf("empty cursor should be zero value, got %+v", c)
	}
}

func TestDecodeCursorRejectsMalformed(t *testing.T) {
	for _, tok := range []string{"!!!not-base64!!!", "Zm9vYmFy", "two|parts|only"} {
		if _, _, err := DecodeCursor(tok); err == nil {
			t.Fatalf("expected error for malformed cursor %q", tok)
		}
	}
}
