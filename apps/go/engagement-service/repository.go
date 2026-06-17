package main

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// postgresRepository is the durable snapshot of the live Redis counters. engagement-service OWNS
// sssm.post_metrics (migrated by infra, never by the app).
type postgresRepository struct {
	pool *pgxpool.Pool
}

func newPostgresRepository(pool *pgxpool.Pool) *postgresRepository {
	return &postgresRepository{pool: pool}
}

// Upsert writes the batch in one round trip. The upsert is idempotent, so a re-flushed batch (after
// a transient failure) is harmless.
func (r *postgresRepository) Upsert(ctx context.Context, metrics []Metrics) error {
	if len(metrics) == 0 {
		return nil
	}
	batch := &pgx.Batch{}
	for _, m := range metrics {
		batch.Queue(`
			INSERT INTO sssm.post_metrics (post_id, views, likes, reposts, updated_at)
			VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (post_id) DO UPDATE SET
				views = EXCLUDED.views,
				likes = EXCLUDED.likes,
				reposts = EXCLUDED.reposts,
				updated_at = EXCLUDED.updated_at`,
			m.PostID, m.Views, m.Likes, m.Reposts, m.UpdatedAt)
	}
	br := r.pool.SendBatch(ctx, batch)
	defer br.Close()
	for range metrics {
		if _, err := br.Exec(); err != nil {
			return err
		}
	}
	return nil
}

func (r *postgresRepository) Get(ctx context.Context, postID uuid.UUID) (Metrics, error) {
	var m Metrics
	err := r.pool.QueryRow(ctx,
		`SELECT post_id, views, likes, reposts, updated_at FROM sssm.post_metrics WHERE post_id = $1`,
		postID).Scan(&m.PostID, &m.Views, &m.Likes, &m.Reposts, &m.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Metrics{}, errMetricsNotFound
	}
	if err != nil {
		return Metrics{}, err
	}
	return m, nil
}

func (r *postgresRepository) Delete(ctx context.Context, postID uuid.UUID) error {
	_, err := r.pool.Exec(ctx, `DELETE FROM sssm.post_metrics WHERE post_id = $1`, postID)
	return err
}
