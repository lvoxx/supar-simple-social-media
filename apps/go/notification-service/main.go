// Command notification-service consumes post-service's domain events from Kafka and turns them into
// per-user notifications: it persists each one (Postgres), pushes it to the recipient's live SSE
// streams, and (stubbed for now) to their registered FCM/APNs devices. It also serves the inbox
// read/ack API. Cross-cutting HTTP concerns come from the shared httpx middleware.
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
	"github.com/segmentio/kafka-go"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	cfg, err := LoadConfig()
	if err != nil {
		logger.Error("invalid configuration", "err", err)
		os.Exit(1)
	}

	// Root context cancelled on SIGINT/SIGTERM so the Kafka consumer and HTTP server drain cleanly.
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

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  cfg.KafkaBrokers,
		Topic:    cfg.KafkaTopic,
		GroupID:  cfg.KafkaGroupID,
		MinBytes: 1,
		MaxBytes: 10e6,
	})
	defer reader.Close()

	// Cross-replica SSE fan-out over Redis pub/sub: Publish broadcasts to a per-recipient channel and
	// every replica serving that recipient delivers to its local streams (hub.Run, below).
	transport := newRedisPubSub(rdb)
	hub := NewRedisHub(transport, logger)
	svc := NewNotificationService(cfg, newPostgresRepository(pool), hub, newLogPusher(logger), logger)
	consumer := NewConsumer(reader, svc, logger)

	// Receive cross-replica notifications until the transport closes (shutdown) or ctx is cancelled.
	hubDone := make(chan struct{})
	go func() {
		defer close(hubDone)
		hub.Run(ctx)
	}()

	// Consume in the background; Run returns nil on graceful ctx cancellation.
	consumerDone := make(chan struct{})
	go func() {
		defer close(consumerDone)
		if err := consumer.Run(ctx); err != nil {
			logger.Error("consumer stopped with error", "err", err)
		}
	}()

	r := gin.New()
	r.Use(httpx.Default(logger)...)
	r.GET("/healthz", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "notification-service"})
	})
	NewHandler(svc, hub, logger).RegisterRoutes(r)

	srv := &http.Server{Addr: cfg.Addr, Handler: r}
	go func() {
		logger.Info("starting notification-service", "addr", cfg.Addr)
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
	reader.Close()
	transport.Close() // ends hub.Run by closing the pub/sub message stream
	<-consumerDone
	<-hubDone
}
