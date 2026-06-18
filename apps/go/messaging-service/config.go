package main

import (
	"fmt"
	"os"
	"strconv"
)

// Config holds the runtime configuration for messaging-service, sourced entirely from the environment
// so the same image runs unchanged across local/staging/prod (12-factor).
type Config struct {
	Addr            string // listen address, e.g. ":8087"
	DatabaseURL     string // pgx connection string for the shared Postgres (owns the dm_* tables)
	RedisURL        string // go-redis URL; backs the cross-replica WebSocket pub/sub (any replica reaches any client)
	DefaultLimit    int    // page size when the client omits ?limit
	MaxLimit        int    // hard cap on ?limit to bound query cost
	MaxMessageBytes int    // largest accepted message body, in bytes
}

// LoadConfig reads configuration from the environment, applying sensible defaults. DATABASE_URL and
// REDIS_URL are required: without a store there is nowhere to persist messages, and without Redis the
// WebSocket fan-out can't span replicas (a sender on replica A must reach a recipient on replica B).
func LoadConfig() (Config, error) {
	cfg := Config{
		Addr:            ":" + getenv("PORT", "8087"),
		DatabaseURL:     os.Getenv("DATABASE_URL"),
		RedisURL:        os.Getenv("REDIS_URL"),
		DefaultLimit:    getenvInt("MESSAGES_DEFAULT_LIMIT", 30),
		MaxLimit:        getenvInt("MESSAGES_MAX_LIMIT", 100),
		MaxMessageBytes: getenvInt("MESSAGES_MAX_BYTES", 4000),
	}
	if cfg.DatabaseURL == "" {
		return Config{}, fmt.Errorf("DATABASE_URL is required")
	}
	if cfg.RedisURL == "" {
		return Config{}, fmt.Errorf("REDIS_URL is required")
	}
	return cfg, nil
}

// clampLimit normalizes a client-requested limit into [1, MaxLimit], falling back to DefaultLimit when
// the request omits or under-specifies the value.
func (c Config) clampLimit(requested int) int {
	if requested <= 0 {
		return c.DefaultLimit
	}
	if requested > c.MaxLimit {
		return c.MaxLimit
	}
	return requested
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
