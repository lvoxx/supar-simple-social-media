package main

import (
	"errors"
	"log/slog"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/lvoxx/sssm/go/common/httpx"
)

// Handler mounts the search-service read API. The index is written only from Kafka; the HTTP surface
// is read-only. Identity comes from the gateway sidecar (X-Auth-Subject) — search requires an
// authenticated caller but the results themselves are public posts.
type Handler struct {
	svc *SearchService
	log *slog.Logger
}

func NewHandler(svc *SearchService, log *slog.Logger) *Handler {
	return &Handler{svc: svc, log: log}
}

func (h *Handler) RegisterRoutes(r gin.IRouter) {
	r.GET("/api/v1/search/posts", h.handleSearchPosts)
}

// handleSearchPosts answers GET /api/v1/search/posts?q=&author=&limit=&cursor=. q is the full-text
// query (empty browses recent posts); author optionally scopes to one author id.
func (h *Handler) handleSearchPosts(c *gin.Context) {
	if _, ok := httpx.AuthSubject(c); !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}

	authorID := c.Query("author")
	if authorID != "" {
		if _, err := uuid.Parse(authorID); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid author id"})
			return
		}
	}

	var limit int
	if raw := c.Query("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 0 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid limit"})
			return
		}
		limit = n
	}

	page, err := h.svc.Search(c.Request.Context(), c.Query("q"), authorID, limit, c.Query("cursor"))
	if err != nil {
		if errors.Is(err, errInvalidCursor) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid cursor"})
			return
		}
		h.log.Error("search failed", "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "search failed"})
		return
	}
	c.JSON(http.StatusOK, page)
}
