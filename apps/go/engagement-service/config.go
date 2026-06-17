package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config holds the runtime configuration for engagement-service, sourced entirely from the
// environment so the same image runs unchanged across local/staging/prod (12-factor).
type Config struct {
	Addr          string        // listen address, e.g. ":8085"
	RedisURL      string        // go-redis URL; holds the live counters (the hot path)
	DatabaseURL   string        // pgx connection string; owns sssm.post_metrics (the durable snapshot)
	KafkaBrokers  []string      // bootstrap brokers for the post-events consumer
	KafkaTopic    string        // topic carrying PostEngagement/PostDeleted (sssm.post-events)
	KafkaGroupID  string        // consumer group; one offset cursor shared by all replicas
	FlushInterval time.Duration // how often dirty counters are flushed to Postgres
	FlushBatch    int           // max posts drained from the dirty set per flush tick
}

// LoadConfig reads configuration from the environment, applying sensible defaults. REDIS_URL,
// DATABASE_URL, and KAFKA_BROKERS are all required: Redis is the live counter store, Postgres is the
// durable snapshot, and Kafka is the only source of engagement signal.
func LoadConfig() (Config, error) {
	cfg := Config{
		Addr:          ":" + getenv("PORT", "8085"),
		RedisURL:      os.Getenv("REDIS_URL"),
		DatabaseURL:   os.Getenv("DATABASE_URL"),
		KafkaBrokers:  splitNonEmpty(getenv("KAFKA_BROKERS", "")),
		KafkaTopic:    getenv("KAFKA_TOPIC", "sssm.post-events"),
		KafkaGroupID:  getenv("KAFKA_GROUP_ID", "engagement-service"),
		FlushInterval: time.Duration(getenvInt("ENGAGEMENT_FLUSH_INTERVAL_SECONDS", 10)) * time.Second,
		FlushBatch:    getenvInt("ENGAGEMENT_FLUSH_BATCH", 500),
	}
	if cfg.RedisURL == "" {
		return Config{}, fmt.Errorf("REDIS_URL is required")
	}
	if cfg.DatabaseURL == "" {
		return Config{}, fmt.Errorf("DATABASE_URL is required")
	}
	if len(cfg.KafkaBrokers) == 0 {
		return Config{}, fmt.Errorf("KAFKA_BROKERS is required")
	}
	return cfg, nil
}

func splitNonEmpty(csv string) []string {
	if csv == "" {
		return nil
	}
	parts := strings.Split(csv, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getenvInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}
