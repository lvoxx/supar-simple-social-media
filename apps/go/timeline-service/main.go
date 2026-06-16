// Command timeline-service is the Gin entrypoint for the home-feed (timeline) service. It serves a
// user's home timeline by fan-out-on-read: at request time it reads the follow graph and the
// followees' recent posts from the shared Postgres, fronted by a short-TTL Redis cache. Cross-cutting
// HTTP concerns (request-ID, structured logging, recovery) come from the shared httpx middleware.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/lvoxx/sssm/go/common/httpx"
	"github.com/redis/go-redis/v9"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	cfg, err := LoadConfig()
	if err != nil {
		logger.Error("invalid configuration", "err", err)
		os.Exit(1)
	}

	ctx := context.Background()

	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		logger.Error("postgres connect failed", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	redisOpts, err := redis.ParseURL(cfg.RedisURL)
	if err != nil {
		logger.Error("invalid REDIS_URL", "err", err)
		os.Exit(1)
	}
	rdb := redis.NewClient(redisOpts)
	defer rdb.Close()

	svc := NewTimelineService(cfg, newPostgresRepository(pool),
		newRedisCache(rdb, cfg.CacheTTL, logger), logger)

	r := gin.New()
	r.Use(httpx.Default(logger)...)
	r.GET("/healthz", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "timeline-service"})
	})
	svc.RegisterRoutes(r)

	logger.Info("starting timeline-service", "addr", cfg.Addr)
	if err := r.Run(cfg.Addr); err != nil {
		logger.Error("server exited", "err", err)
		os.Exit(1)
	}
}
