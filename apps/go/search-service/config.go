package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// Config holds the runtime configuration for search-service, sourced entirely from the environment
// so the same image runs unchanged across local/staging/prod (12-factor).
type Config struct {
	Addr          string   // listen address, e.g. ":8086"
	OpenSearchURL string   // base URL of the OpenSearch cluster, e.g. http://opensearch:9200
	IndexName     string   // index that holds post documents (default "posts")
	KafkaBrokers  []string // bootstrap brokers for the post-events consumer
	KafkaTopic    string   // topic carrying PostCreated/PostDeleted (sssm.post-events)
	KafkaGroupID  string   // consumer group; one offset cursor shared by all replicas
	DefaultLimit  int      // page size when the caller omits limit
	MaxLimit      int      // hard cap on page size
}

// LoadConfig reads configuration from the environment, applying sensible defaults. OPENSEARCH_URL and
// KAFKA_BROKERS are required: OpenSearch is the search index and Kafka is the only source of post
// documents (the index is rebuilt by replaying the topic, never written by the HTTP surface).
func LoadConfig() (Config, error) {
	cfg := Config{
		Addr:          ":" + getenv("PORT", "8086"),
		OpenSearchURL: strings.TrimRight(os.Getenv("OPENSEARCH_URL"), "/"),
		IndexName:     getenv("SEARCH_INDEX", "posts"),
		KafkaBrokers:  splitNonEmpty(getenv("KAFKA_BROKERS", "")),
		KafkaTopic:    getenv("KAFKA_TOPIC", "sssm.post-events"),
		KafkaGroupID:  getenv("KAFKA_GROUP_ID", "search-service"),
		DefaultLimit:  getenvInt("SEARCH_DEFAULT_LIMIT", 20),
		MaxLimit:      getenvInt("SEARCH_MAX_LIMIT", 50),
	}
	if cfg.OpenSearchURL == "" {
		return Config{}, fmt.Errorf("OPENSEARCH_URL is required")
	}
	if len(cfg.KafkaBrokers) == 0 {
		return Config{}, fmt.Errorf("KAFKA_BROKERS is required")
	}
	return cfg, nil
}

// clampLimit applies the configured default and cap to a caller-supplied page size.
func (c Config) clampLimit(n int) int {
	if n <= 0 {
		return c.DefaultLimit
	}
	if n > c.MaxLimit {
		return c.MaxLimit
	}
	return n
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
