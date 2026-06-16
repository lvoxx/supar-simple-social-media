package main

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// postgresRepository reads the follow graph and posts from the shared `sssm` schema. timeline-service
// is a READ-ONLY consumer of tables owned (and migrated) by user-service and post-service — it never
// writes them and never runs migrations (see the DB-init-is-infra-only rule). This cross-service
// read is a deliberate Phase 1 budget trade-off (single RDS); Phase 2 replaces it with materialized
// timelines fed by Kafka events.
type postgresRepository struct {
	pool *pgxpool.Pool
}

func newPostgresRepository(pool *pgxpool.Pool) *postgresRepository {
	return &postgresRepository{pool: pool}
}

func (r *postgresRepository) Followees(ctx context.Context, userID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := r.pool.Query(ctx,
		`SELECT followee_id FROM sssm.follows WHERE follower_id = $1`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	ids := make([]uuid.UUID, 0)
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func (r *postgresRepository) Posts(ctx context.Context, authorIDs []uuid.UUID, after Cursor, hasAfter bool, limit int) ([]Post, error) {
	// Keyset pagination on (created_at, id): the cursor predicate is applied only on cursored pages.
	// idx_posts_author covers (author_id, created_at DESC, id DESC) so the scan stays index-only-ish
	// and bounded. The row tuple comparison `(created_at, id) < ($,$)` is the standard keyset form.
	const base = `
		SELECT id, author_id, text, reply_to_post_id,
		       reply_count, like_count, repost_count, bookmark_count, created_at
		FROM sssm.posts
		WHERE author_id = ANY($1)`
	const order = ` ORDER BY created_at DESC, id DESC LIMIT `

	var rows pgx.Rows
	var err error
	if hasAfter {
		rows, err = r.pool.Query(ctx,
			base+` AND (created_at, id) < ($2, $3)`+order+`$4`,
			authorIDs, after.CreatedAt, after.ID, limit)
	} else {
		rows, err = r.pool.Query(ctx, base+order+`$2`, authorIDs, limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	posts := make([]Post, 0, limit)
	for rows.Next() {
		var p Post
		if err := rows.Scan(
			&p.ID, &p.AuthorID, &p.Text, &p.ReplyToPostID,
			&p.ReplyCount, &p.LikeCount, &p.RepostCount, &p.BookmarkCount, &p.CreatedAt,
		); err != nil {
			return nil, err
		}
		posts = append(posts, p)
	}
	return posts, rows.Err()
}
