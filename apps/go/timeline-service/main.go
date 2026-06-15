// Command timeline-service is the Gin entrypoint for the home-feed (timeline) service.
// It demonstrates reuse of the shared gokit middleware (resolved via the Go workspace).
package main

import (
	"log/slog"
	"net/http"
	"os"

	"github.com/gin-gonic/gin"
	"github.com/lvoxx/sssm/go/common/httpx"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	r := gin.New()
	r.Use(httpx.Default(logger)...)

	r.GET("/healthz", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "timeline-service"})
	})

	addr := ":8080"
	if v := os.Getenv("PORT"); v != "" {
		addr = ":" + v
	}
	logger.Info("starting timeline-service", "addr", addr)
	if err := r.Run(addr); err != nil {
		logger.Error("server exited", "err", err)
		os.Exit(1)
	}
}
