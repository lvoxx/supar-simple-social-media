package main

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// postgresRepository persists the dm_conversations/dm_messages tables this service OWNS (in the shared
// `sssm` schema, migrated by infra — never by the app). It is the only service that writes these
// tables; no cross-service reads are needed (a DM's addressing lives entirely in its own tables).
type postgresRepository struct {
	pool *pgxpool.Pool
}

func newPostgresRepository(pool *pgxpool.Pool) *postgresRepository {
	return &postgresRepository{pool: pool}
}

// GetOrCreateConversation returns the conversation for the unordered pair {a, b}, creating it if it
// does not exist. The pair is stored canonically ordered so the same two users always map to one row.
// ON CONFLICT DO UPDATE (a no-op SET) is used instead of DO NOTHING so RETURNING yields the existing
// row on conflict, making the call a single idempotent round-trip.
func (r *postgresRepository) GetOrCreateConversation(ctx context.Context, a, b uuid.UUID) (Conversation, error) {
	lo, hi := orderPair(a, b)
	const q = `
		INSERT INTO sssm.dm_conversations (user_lo, user_hi)
		VALUES ($1, $2)
		ON CONFLICT (user_lo, user_hi) DO UPDATE SET user_lo = EXCLUDED.user_lo
		RETURNING id, user_lo, user_hi, created_at, last_message_at`
	var c Conversation
	err := r.pool.QueryRow(ctx, q, lo, hi).Scan(&c.ID, &c.UserLo, &c.UserHi, &c.CreatedAt, &c.LastMessageAt)
	if err != nil {
		return Conversation{}, err
	}
	return c, nil
}

// InsertMessage persists a message and bumps its conversation's last_message_at to the message's
// timestamp, atomically, so the sender's conversation list re-sorts to the top. The two writes run in
// one transaction: a message must never exist without its conversation reflecting the activity.
func (r *postgresRepository) InsertMessage(ctx context.Context, m Message) (Message, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return Message{}, err
	}
	defer tx.Rollback(ctx)

	const insert = `
		INSERT INTO sssm.dm_messages (conversation_id, sender_id, recipient_id, body)
		VALUES ($1, $2, $3, $4)
		RETURNING id, conversation_id, sender_id, recipient_id, body, created_at`
	stored := m
	if err := tx.QueryRow(ctx, insert, m.ConversationID, m.SenderID, m.RecipientID, m.Body).
		Scan(&stored.ID, &stored.ConversationID, &stored.SenderID, &stored.RecipientID, &stored.Body, &stored.CreatedAt); err != nil {
		return Message{}, err
	}

	if _, err := tx.Exec(ctx,
		`UPDATE sssm.dm_conversations SET last_message_at = $2 WHERE id = $1`,
		stored.ConversationID, stored.CreatedAt); err != nil {
		return Message{}, err
	}

	if err := tx.Commit(ctx); err != nil {
		return Message{}, err
	}
	return stored, nil
}

// ListConversations returns a participant's conversations, most-recently-active first, keyset-paged on
// (last_message_at, id). A participant may be either side of the canonical pair, hence the OR.
func (r *postgresRepository) ListConversations(ctx context.Context, userID uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Conversation, error) {
	const base = `
		SELECT id, user_lo, user_hi, created_at, last_message_at
		FROM sssm.dm_conversations
		WHERE (user_lo = $1 OR user_hi = $1)`
	const order = ` ORDER BY last_message_at DESC, id DESC LIMIT `

	var rows pgx.Rows
	var err error
	if hasAfter {
		rows, err = r.pool.Query(ctx,
			base+` AND (last_message_at, id) < ($2, $3)`+order+`$4`,
			userID, after.TS, after.ID, limit)
	} else {
		rows, err = r.pool.Query(ctx, base+order+`$2`, userID, limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	items := make([]Conversation, 0, limit)
	for rows.Next() {
		var c Conversation
		if err := rows.Scan(&c.ID, &c.UserLo, &c.UserHi, &c.CreatedAt, &c.LastMessageAt); err != nil {
			return nil, err
		}
		items = append(items, c)
	}
	return items, rows.Err()
}

// ListMessages returns a conversation's messages, newest first, keyset-paged on (created_at, id). The
// participant join is a hard authorization guard: a caller who is not in the conversation gets an
// empty page and so can never read or probe a thread they are not part of.
func (r *postgresRepository) ListMessages(ctx context.Context, conversationID, userID uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Message, error) {
	const base = `
		SELECT m.id, m.conversation_id, m.sender_id, m.recipient_id, m.body, m.created_at
		FROM sssm.dm_messages m
		JOIN sssm.dm_conversations c ON c.id = m.conversation_id
		WHERE m.conversation_id = $1 AND ($2 = c.user_lo OR $2 = c.user_hi)`
	const order = ` ORDER BY m.created_at DESC, m.id DESC LIMIT `

	var rows pgx.Rows
	var err error
	if hasAfter {
		rows, err = r.pool.Query(ctx,
			base+` AND (m.created_at, m.id) < ($3, $4)`+order+`$5`,
			conversationID, userID, after.TS, after.ID, limit)
	} else {
		rows, err = r.pool.Query(ctx, base+order+`$3`, conversationID, userID, limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	items := make([]Message, 0, limit)
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.ConversationID, &m.SenderID, &m.RecipientID, &m.Body, &m.CreatedAt); err != nil {
			return nil, err
		}
		items = append(items, m)
	}
	return items, rows.Err()
}
