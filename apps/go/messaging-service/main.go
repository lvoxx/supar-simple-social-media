// Command messaging-service serves 1:1 direct messages over WebSocket. Clients open a single socket
// (GET /api/v1/messages/stream) to send DMs and receive them live; a REST surface serves the
// conversation list and message history with keyset pagination. Each message is persisted (Postgres,
// the durable record) and fanned out to both participants over a Redis pub/sub channel per user, so a
// recipient connected to any replica receives it regardless of which replica the sender used. Identity
// is gateway-trusted (X-Auth-Subject); cross-cutting HTTP concerns come from the shared httpx
// middleware. There is no Kafka consumer — DMs originate from the API, not from domain events.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

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

	// Root context cancelled on SIGINT/SIGTERM so the hub and HTTP server drain cleanly.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

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

	// Cross-replica WebSocket fan-out over Redis pub/sub: Publish broadcasts to a per-user channel and
	// every replica serving that user delivers to its local sessions (hub.Run, below).
	transport := newRedisPubSub(rdb)
	hub := NewRedisHub(transport, logger)
	svc := NewMessagingService(cfg, newPostgresRepository(pool), hub, logger)
	session := NewSession(svc, hub, logger)

	// Receive cross-replica messages until the transport closes (shutdown) or ctx is cancelled.
	hubDone := make(chan struct{})
	go func() {
		defer close(hubDone)
		hub.Run(ctx)
	}()

	r := gin.New()
	r.Use(httpx.Default(logger)...)
	r.GET("/healthz", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "messaging-service"})
	})
	NewHandler(svc, session, cfg.MaxMessageBytes, logger).RegisterRoutes(r)

	srv := &http.Server{Addr: cfg.Addr, Handler: r}
	go func() {
		logger.Info("starting messaging-service", "addr", cfg.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server exited", "err", err)
			stop() // trigger shutdown of the rest
		}
	}()

	<-ctx.Done()
	logger.Info("shutting down")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("http shutdown failed", "err", err)
	}
	transport.Close() // ends hub.Run by closing the pub/sub message stream
	<-hubDone
}
