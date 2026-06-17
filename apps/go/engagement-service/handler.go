package main

import (
	"log/slog"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/lvoxx/sssm/go/common/httpx"
)

// Handler mounts the engagement-service read API. Counters are written only from Kafka; the HTTP
// surface is read-only. Identity comes from the gateway sidecar (X-Auth-Subject) — metrics are not
// public in Slice 1.
type Handler struct {
	svc *EngagementService
	log *slog.Logger
}

func NewHandler(svc *EngagementService, log *slog.Logger) *Handler {
	return &Handler{svc: svc, log: log}
}

func (h *Handler) RegisterRoutes(r gin.IRouter) {
	// Dedicated /api/v1/metrics prefix (not /api/v1/posts/*) so the path-prefix ingress routes it
	// cleanly to this service without colliding with post-service's /api/v1/posts.
	r.GET("/api/v1/metrics/posts/:id", h.handleGetMetrics)
}

func (h *Handler) handleGetMetrics(c *gin.Context) {
	if _, ok := httpx.AuthSubject(c); !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	postID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid post id"})
		return
	}
	m, err := h.svc.Metrics(c.Request.Context(), postID)
	if err != nil {
		h.log.Error("read metrics failed", "post_id", postID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load metrics"})
		return
	}
	c.JSON(http.StatusOK, m)
}
