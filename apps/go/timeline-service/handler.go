package main

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/lvoxx/sssm/go/common/httpx"
)

// errInvalidCursor is returned when a client supplies a non-empty but unparseable cursor; the
// handler maps it to 400 so a bad token is a client error, not a silent reset to the first page.
var errInvalidCursor = errors.New("invalid cursor")

// RegisterRoutes mounts the timeline-service HTTP API on the given router.
func (s *TimelineService) RegisterRoutes(r gin.IRouter) {
	r.GET("/api/v1/timeline/home", s.handleHomeTimeline)
}

// handleHomeTimeline serves the authenticated caller's home feed. Identity comes from the gateway
// (X-Auth-Subject); there is no public/anonymous home timeline, so a missing subject is a 401.
func (s *TimelineService) handleHomeTimeline(c *gin.Context) {
	userID, ok := httpx.AuthSubject(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}

	limit := 0
	if raw := c.Query("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "limit must be an integer"})
			return
		}
		limit = n
	}

	page, err := s.HomeTimeline(c.Request.Context(), userID, c.Query("cursor"), limit)
	if err != nil {
		if errors.Is(err, errInvalidCursor) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid cursor"})
			return
		}
		s.log.Error("home timeline failed", "user_id", userID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load timeline"})
		return
	}
	c.JSON(http.StatusOK, page)
}
