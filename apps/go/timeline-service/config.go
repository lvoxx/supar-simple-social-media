package main

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// Config holds the runtime configuration for timeline-service, sourced entirely from the
// environment so the same image runs unchanged across local/staging/prod (12-factor).
type Config struct {
	Addr         string        // listen address, e.g. ":8080"
	DatabaseURL  string        // pgx connection string for the shared Postgres (read-only use)
	RedisURL     string        // go-redis URL, e.g. redis://host:6379/0
	DefaultLimit int           // page size when the client omits ?limit
	MaxLimit     int           // hard cap on ?limit to bound query cost
	CacheTTL     time.Duration // how long a materialized first page / followee set stays warm
}

// LoadConfig reads configuration from the environment, applying sensible defaults. DATABASE_URL and
// REDIS_URL are required because a timeline with neither a feed source nor a cache is meaningless.
func LoadConfig() (Config, error) {
	cfg := Config{
		Addr:         ":" + getenv("PORT", "8080"),
		DatabaseURL:  os.Getenv("DATABASE_URL"),
		RedisURL:     os.Getenv("REDIS_URL"),
		DefaultLimit: getenvInt("TIMELINE_DEFAULT_LIMIT", 20),
		MaxLimit:     getenvInt("TIMELINE_MAX_LIMIT", 100),
		CacheTTL:     time.Duration(getenvInt("TIMELINE_CACHE_TTL_SECONDS", 30)) * time.Second,
	}
	if cfg.DatabaseURL == "" {
		return Config{}, fmt.Errorf("DATABASE_URL is required")
	}
	if cfg.RedisURL == "" {
		return Config{}, fmt.Errorf("REDIS_URL is required")
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
