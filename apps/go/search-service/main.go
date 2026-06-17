// Command search-service maintains a full-text post index in OpenSearch, fed entirely by
// post-service's Kafka events (PostCreated indexes, PostDeleted removes), and serves a read-only
// search API. The index is a derived read model that can be rebuilt by replaying the topic.
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
	"github.com/lvoxx/sssm/go/common/httpx"
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

	idx := newOSIndex(cfg.OpenSearchURL, cfg.IndexName, &http.Client{Timeout: 10 * time.Second})

	// Create the index/mapping if absent. A failure here is non-fatal: OpenSearch may still be
	// starting, and IndexPost auto-creates a (less precisely mapped) index on first write.
	ensureCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	if err := idx.EnsureIndex(ensureCtx); err != nil {
		logger.Warn("ensure index failed; will rely on auto-create", "err", err)
	}
	cancel()

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  cfg.KafkaBrokers,
		Topic:    cfg.KafkaTopic,
		GroupID:  cfg.KafkaGroupID,
		MinBytes: 1,
		MaxBytes: 10e6,
	})
	defer reader.Close()

	svc := NewSearchService(cfg, idx, logger)
	consumer := NewConsumer(reader, svc, logger)

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
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "search-service"})
	})
	NewHandler(svc, logger).RegisterRoutes(r)

	srv := &http.Server{Addr: cfg.Addr, Handler: r}
	go func() {
		logger.Info("starting search-service", "addr", cfg.Addr)
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
}
