package main

import (
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestCursorRoundTrip(t *testing.T) {
	want := Cursor{
		CreatedAt: time.Date(2026, 6, 16, 10, 30, 0, 123456789, time.UTC),
		ID:        uuid.MustParse("11111111-1111-1111-1111-111111111111"),
	}

	got, ok, err := DecodeCursor(want.Encode())
	if err != nil {
		t.Fatalf("DecodeCursor returned error: %v", err)
	}
	if !ok {
		t.Fatal("DecodeCursor reported empty cursor for a real token")
	}
	if !got.CreatedAt.Equal(want.CreatedAt) {
		t.Errorf("CreatedAt = %v, want %v", got.CreatedAt, want.CreatedAt)
	}
	if got.ID != want.ID {
		t.Errorf("ID = %v, want %v", got.ID, want.ID)
	}
}

func TestDecodeCursorEmpty(t *testing.T) {
	c, ok, err := DecodeCursor("")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ok {
		t.Error("empty token should report ok=false")
	}
	if c != (Cursor{}) {
		t.Errorf("empty token should yield zero cursor, got %+v", c)
	}
}

func TestDecodeCursorMalformed(t *testing.T) {
	for _, tok := range []string{"not-base64!!", "Zm9v", "$$$"} {
		if _, _, err := DecodeCursor(tok); err == nil {
			t.Errorf("DecodeCursor(%q) = nil error, want error", tok)
		}
	}
}
