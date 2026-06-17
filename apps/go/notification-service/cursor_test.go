package main

import (
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestCursorRoundTrip(t *testing.T) {
	want := Cursor{
		CreatedAt: time.Date(2026, 6, 17, 9, 30, 15, 123456789, time.UTC),
		ID:        uuid.New(),
	}
	got, ok, err := DecodeCursor(want.Encode())
	if err != nil || !ok {
		t.Fatalf("decode failed: ok=%v err=%v", ok, err)
	}
	if got.ID != want.ID || !got.CreatedAt.Equal(want.CreatedAt) {
		t.Errorf("round trip mismatch: got %+v want %+v", got, want)
	}
}

func TestDecodeEmptyCursorIsFirstPage(t *testing.T) {
	_, ok, err := DecodeCursor("")
	if err != nil {
		t.Fatalf("empty cursor should not error: %v", err)
	}
	if ok {
		t.Error("empty cursor should report ok=false (start from newest)")
	}
}

func TestDecodeMalformedCursorErrors(t *testing.T) {
	for _, tok := range []string{"not-base64!!", "Zm9vfGJhcg"} { // bad encoding; valid b64 but "foo|bar"
		if _, _, err := DecodeCursor(tok); err == nil {
			t.Errorf("token %q should be rejected", tok)
		}
	}
}
