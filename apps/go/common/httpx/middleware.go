// Package httpx provides shared Gin middleware for all SSSM Go services so that request ID
// propagation, panic recovery, and structured access logging are identical across the fleet.
package httpx

import (
	"log/slog"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// HeaderRequestID is the canonical request-correlation header used across SSSM services.
const HeaderRequestID = "X-Request-ID"

// RequestID ensures every request carries a correlation ID, generating one when absent and
// echoing it back on the response so it can be threaded through logs and traces.
func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.GetHeader(HeaderRequestID)
		if id == "" {
			id = uuid.NewString()
		}
		c.Set("request_id", id)
		c.Writer.Header().Set(HeaderRequestID, id)
		c.Next()
	}
}

// AccessLog emits one structured log line per request with method, path, status, latency, and the
// correlation ID.
func AccessLog(logger *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		logger.Info("http_request",
			"method", c.Request.Method,
			"path", c.FullPath(),
			"status", c.Writer.Status(),
			"latency_ms", time.Since(start).Milliseconds(),
			"request_id", c.GetString("request_id"),
		)
	}
}

// Default returns the baseline middleware chain every SSSM service should mount: recovery,
// request-ID correlation, and access logging.
func Default(logger *slog.Logger) []gin.HandlerFunc {
	return []gin.HandlerFunc{gin.Recovery(), RequestID(), AccessLog(logger)}
}
