// Command engagement-service maintains high-throughput per-post engagement counters
// (views/likes/reposts) in Redis from post-service's Kafka events, periodically flushing them to a
// durable Postgres snapshot, and serves a read-only metrics API. These counters feed ranking and are
// distinct from post-service's transactional display counts.
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

	svc := NewEngagementService(cfg, newRedisCounters(rdb), newPostgresRepository(pool), logger)
	consumer := NewConsumer(reader, svc, logger)

	consumerDone := make(chan struct{})
	go func() {
		defer close(consumerDone)
		if err := consumer.Run(ctx); err != nil {
			logger.Error("consumer stopped with error", "err", err)
		}
	}()

	// Periodic flusher: drains dirty counters into the Postgres snapshot. A final flush runs on
	// shutdown so the snapshot is current before exit.
	flusherDone := make(chan struct{})
	go func() {
		defer close(flusherDone)
		ticker := time.NewTicker(cfg.FlushInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				flushCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				if n, err := svc.Flush(flushCtx); err != nil {
					logger.Error("final flush failed", "err", err)
				} else if n > 0 {
					logger.Info("final flush complete", "posts", n)
				}
				cancel()
				return
			case <-ticker.C:
				if n, err := svc.Flush(ctx); err != nil {
					logger.Error("flush failed", "err", err)
				} else if n > 0 {
					logger.Debug("flushed counters", "posts", n)
				}
			}
		}
	}()

	r := gin.New()
	r.Use(httpx.Default(logger)...)
	r.GET("/healthz", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "engagement-service"})
	})
	NewHandler(svc, logger).RegisterRoutes(r)

	srv := &http.Server{Addr: cfg.Addr, Handler: r}
	go func() {
		logger.Info("starting engagement-service", "addr", cfg.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server exited", "err", err)
			stop()
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
	<-consumerDone
	<-flusherDone
}
