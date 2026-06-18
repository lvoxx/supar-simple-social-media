package main

import (
	"encoding/base64"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
)

// Cursor is the keyset position shared by both paginated lists (conversations by last_message_at,
// messages by created_at). Both order by (timestamp DESC, id DESC); a cursor captures the last item of
// a page so the next page resumes strictly after it without OFFSET, staying stable as new rows arrive
// at the head.
type Cursor struct {
	TS time.Time
	ID uuid.UUID
}

// Encode renders the cursor as an opaque URL-safe token. Clients echo it back verbatim and must not
// parse it.
func (c Cursor) Encode() string {
	raw := fmt.Sprintf("%s|%s", c.TS.UTC().Format(time.RFC3339Nano), c.ID)
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

// DecodeCursor parses a token produced by Cursor.Encode. An empty token yields a zero Cursor with
// ok=false ("start from the newest"). A non-empty but malformed token is an error so the handler can
// reject it with 400 rather than silently serving the first page.
func DecodeCursor(token string) (Cursor, bool, error) {
	if token == "" {
		return Cursor{}, false, nil
	}
	decoded, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return Cursor{}, false, fmt.Errorf("invalid cursor encoding: %w", err)
	}
	parts := strings.SplitN(string(decoded), "|", 2)
	if len(parts) != 2 {
		return Cursor{}, false, fmt.Errorf("invalid cursor format")
	}
	ts, err := time.Parse(time.RFC3339Nano, parts[0])
	if err != nil {
		return Cursor{}, false, fmt.Errorf("invalid cursor timestamp: %w", err)
	}
	id, err := uuid.Parse(parts[1])
	if err != nil {
		return Cursor{}, false, fmt.Errorf("invalid cursor id: %w", err)
	}
	return Cursor{TS: ts, ID: id}, true, nil
}
