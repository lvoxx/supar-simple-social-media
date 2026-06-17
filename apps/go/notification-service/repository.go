package main

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// postgresRepository persists the notifications/device_tokens tables this service OWNS (in the shared
// `sssm` schema, migrated by infra — never by the app) and READS post-service's `sssm.posts` to
// resolve a post to its author. The cross-service read mirrors timeline-service's single-RDS
// trade-off; this service never writes `sssm.posts`.
type postgresRepository struct {
	pool *pgxpool.Pool
}

func newPostgresRepository(pool *pgxpool.Pool) *postgresRepository {
	return &postgresRepository{pool: pool}
}

func (r *postgresRepository) ResolvePostAuthor(ctx context.Context, postID uuid.UUID) (uuid.UUID, error) {
	var author uuid.UUID
	err := r.pool.QueryRow(ctx, `SELECT author_id FROM sssm.posts WHERE id = $1`, postID).Scan(&author)
	if errors.Is(err, pgx.ErrNoRows) {
		return uuid.Nil, errPostNotFound
	}
	if err != nil {
		return uuid.Nil, err
	}
	return author, nil
}

// Insert is idempotent: the unique (recipient_id, actor_id, kind, post_id) constraint dedupes
// at-least-once redelivery. ON CONFLICT DO NOTHING means a duplicate returns no row, so QueryRow
// reports pgx.ErrNoRows and inserted is false.
func (r *postgresRepository) Insert(ctx context.Context, n Notification) (Notification, bool, error) {
	const q = `
		INSERT INTO sssm.notifications (recipient_id, actor_id, kind, post_id, reply_post_id, created_at)
		VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (recipient_id, actor_id, kind, post_id) DO NOTHING
		RETURNING id, created_at, read_at`
	stored := n
	err := r.pool.QueryRow(ctx, q,
		n.RecipientID, n.ActorID, n.Kind, n.PostID, n.ReplyPostID, n.CreatedAt,
	).Scan(&stored.ID, &stored.CreatedAt, &stored.ReadAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Notification{}, false, nil
	}
	if err != nil {
		return Notification{}, false, err
	}
	return stored, true, nil
}

func (r *postgresRepository) List(ctx context.Context, recipientID uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Notification, error) {
	// Keyset pagination on (created_at, id); idx_notifications_recipient covers
	// (recipient_id, created_at DESC, id DESC) so the scan stays bounded.
	const base = `
		SELECT id, recipient_id, actor_id, kind, post_id, reply_post_id, created_at, read_at
		FROM sssm.notifications
		WHERE recipient_id = $1`
	const order = ` ORDER BY created_at DESC, id DESC LIMIT `

	var rows pgx.Rows
	var err error
	if hasAfter {
		rows, err = r.pool.Query(ctx,
			base+` AND (created_at, id) < ($2, $3)`+order+`$4`,
			recipientID, after.CreatedAt, after.ID, limit)
	} else {
		rows, err = r.pool.Query(ctx, base+order+`$2`, recipientID, limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	items := make([]Notification, 0, limit)
	for rows.Next() {
		var n Notification
		if err := rows.Scan(
			&n.ID, &n.RecipientID, &n.ActorID, &n.Kind,
			&n.PostID, &n.ReplyPostID, &n.CreatedAt, &n.ReadAt,
		); err != nil {
			return nil, err
		}
		items = append(items, n)
	}
	return items, rows.Err()
}

func (r *postgresRepository) UnreadCount(ctx context.Context, recipientID uuid.UUID) (int64, error) {
	var count int64
	err := r.pool.QueryRow(ctx,
		`SELECT count(*) FROM sssm.notifications WHERE recipient_id = $1 AND read_at IS NULL`,
		recipientID).Scan(&count)
	return count, err
}

func (r *postgresRepository) MarkAllRead(ctx context.Context, recipientID uuid.UUID) (int64, error) {
	tag, err := r.pool.Exec(ctx,
		`UPDATE sssm.notifications SET read_at = now() WHERE recipient_id = $1 AND read_at IS NULL`,
		recipientID)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

func (r *postgresRepository) DeleteByPost(ctx context.Context, postID uuid.UUID) (int64, error) {
	tag, err := r.pool.Exec(ctx,
		`DELETE FROM sssm.notifications WHERE post_id = $1`, postID)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

// RegisterDevice upserts on the globally-unique (platform, token): a device re-registering simply
// re-points its token at the current user.
func (r *postgresRepository) RegisterDevice(ctx context.Context, d Device) error {
	_, err := r.pool.Exec(ctx, `
		INSERT INTO sssm.device_tokens (user_id, platform, token)
		VALUES ($1, $2, $3)
		ON CONFLICT (platform, token) DO UPDATE SET user_id = EXCLUDED.user_id, updated_at = now()`,
		d.UserID, d.Platform, d.Token)
	return err
}

func (r *postgresRepository) UnregisterDevice(ctx context.Context, platform, token string) error {
	_, err := r.pool.Exec(ctx,
		`DELETE FROM sssm.device_tokens WHERE platform = $1 AND token = $2`, platform, token)
	return err
}
