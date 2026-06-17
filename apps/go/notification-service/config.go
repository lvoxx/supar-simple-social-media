package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// Config holds the runtime configuration for notification-service, sourced entirely from the
// environment so the same image runs unchanged across local/staging/prod (12-factor).
type Config struct {
	Addr         string   // listen address, e.g. ":8084"
	DatabaseURL  string   // pgx connection string for the shared Postgres (owns the notifications tables)
	KafkaBrokers []string // bootstrap brokers for the post-events consumer
	KafkaTopic   string   // topic carrying PostCreated/PostDeleted/PostEngagement (sssm.post-events)
	KafkaGroupID string   // consumer group; one offset cursor shared by all replicas
	DefaultLimit int      // page size when the client omits ?limit
	MaxLimit     int      // hard cap on ?limit to bound query cost
}

// LoadConfig reads configuration from the environment, applying sensible defaults. DATABASE_URL and
// KAFKA_BROKERS are required: without a store there is nowhere to persist notifications, and without
// a broker there is no event source to turn into them.
func LoadConfig() (Config, error) {
	cfg := Config{
		Addr:         ":" + getenv("PORT", "8084"),
		DatabaseURL:  os.Getenv("DATABASE_URL"),
		KafkaBrokers: splitNonEmpty(getenv("KAFKA_BROKERS", "")),
		KafkaTopic:   getenv("KAFKA_TOPIC", "sssm.post-events"),
		KafkaGroupID: getenv("KAFKA_GROUP_ID", "notification-service"),
		DefaultLimit: getenvInt("NOTIFICATIONS_DEFAULT_LIMIT", 20),
		MaxLimit:     getenvInt("NOTIFICATIONS_MAX_LIMIT", 100),
	}
	if cfg.DatabaseURL == "" {
		return Config{}, fmt.Errorf("DATABASE_URL is required")
	}
	if len(cfg.KafkaBrokers) == 0 {
		return Config{}, fmt.Errorf("KAFKA_BROKERS is required")
	}
	return cfg, nil
}

// clampLimit normalizes a client-requested limit into [1, MaxLimit], falling back to DefaultLimit
// when the request omits or under-specifies the value.
func (c Config) clampLimit(requested int) int {
	if requested <= 0 {
		return c.DefaultLimit
	}
	if requested > c.MaxLimit {
		return c.MaxLimit
	}
	return requested
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
