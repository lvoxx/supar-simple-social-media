package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/lvoxx/sssm/go/common/httpx"
)

// StreamHub is the slice of the hub the handler needs: subscribing a live SSE stream for a user.
// *RedisHub implements it; tests can supply a fake.
type StreamHub interface {
	Subscribe(userID uuid.UUID) (<-chan Notification, func())
}

// Handler mounts the notification-service HTTP API. It wraps the service (inbox read/ack, device
// registration) and the hub (live SSE subscription). Identity comes from the gateway sidecar
// (X-Auth-Subject); every route is per-caller, so a missing subject is always a 401.
type Handler struct {
	svc *NotificationService
	hub StreamHub
	log *slog.Logger
}

func NewHandler(svc *NotificationService, hub StreamHub, log *slog.Logger) *Handler {
	return &Handler{svc: svc, hub: hub, log: log}
}

// RegisterRoutes mounts the notification-service HTTP API on the given router.
func (h *Handler) RegisterRoutes(r gin.IRouter) {
	g := r.Group("/api/v1/notifications")
	g.GET("", h.handleList)
	g.GET("/unread-count", h.handleUnreadCount)
	g.POST("/read", h.handleMarkAllRead)
	g.GET("/stream", h.handleStream)
	g.POST("/devices", h.handleRegisterDevice)
	g.DELETE("/devices", h.handleUnregisterDevice)
}

func (h *Handler) handleList(c *gin.Context) {
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

	page, err := h.svc.List(c.Request.Context(), userID, c.Query("cursor"), limit)
	if err != nil {
		if errors.Is(err, errInvalidCursor) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid cursor"})
			return
		}
		h.log.Error("list notifications failed", "user_id", userID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load notifications"})
		return
	}
	c.JSON(http.StatusOK, page)
}

func (h *Handler) handleUnreadCount(c *gin.Context) {
	userID, ok := httpx.AuthSubject(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	count, err := h.svc.UnreadCount(c.Request.Context(), userID)
	if err != nil {
		h.log.Error("unread count failed", "user_id", userID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to count notifications"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"unreadCount": count})
}

func (h *Handler) handleMarkAllRead(c *gin.Context) {
	userID, ok := httpx.AuthSubject(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	n, err := h.svc.MarkAllRead(c.Request.Context(), userID)
	if err != nil {
		h.log.Error("mark all read failed", "user_id", userID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to mark notifications read"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"markedRead": n})
}

// handleStream serves a Server-Sent Events stream of the caller's new notifications. Each event is
// `event: notification` with a JSON data line; a `: ping` comment every 25s keeps proxies from
// idling the connection out. The stream ends when the client disconnects (request context cancels).
func (h *Handler) handleStream(c *gin.Context) {
	userID, ok := httpx.AuthSubject(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}

	ch, unsubscribe := h.hub.Subscribe(userID)
	defer unsubscribe()

	c.Writer.Header().Set("Content-Type", "text/event-stream")
	c.Writer.Header().Set("Cache-Control", "no-cache")
	c.Writer.Header().Set("Connection", "keep-alive")
	c.Writer.Header().Set("X-Accel-Buffering", "no") // tell nginx not to buffer the stream

	heartbeat := time.NewTicker(25 * time.Second)
	defer heartbeat.Stop()

	c.Stream(func(w io.Writer) bool {
		select {
		case n, open := <-ch:
			if !open {
				return false
			}
			data, err := json.Marshal(n)
			if err != nil {
				h.log.Error("marshal sse notification failed", "err", err)
				return true
			}
			fmt.Fprintf(w, "event: notification\ndata: %s\n\n", data)
			return true
		case <-heartbeat.C:
			fmt.Fprint(w, ": ping\n\n")
			return true
		case <-c.Request.Context().Done():
			return false
		}
	})
}

// deviceRequest is the body for device registration/unregistration.
type deviceRequest struct {
	Platform string `json:"platform"`
	Token    string `json:"token"`
}

func (h *Handler) handleRegisterDevice(c *gin.Context) {
	userID, ok := httpx.AuthSubject(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	var req deviceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
		return
	}
	if err := h.svc.RegisterDevice(c.Request.Context(), userID, req.Platform, req.Token); err != nil {
		if errors.Is(err, errInvalidDevice) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "platform must be FCM or APNS and token must be non-empty"})
			return
		}
		h.log.Error("register device failed", "user_id", userID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to register device"})
		return
	}
	c.Status(http.StatusNoContent)
}

func (h *Handler) handleUnregisterDevice(c *gin.Context) {
	if _, ok := httpx.AuthSubject(c); !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	var req deviceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
		return
	}
	if err := h.svc.UnregisterDevice(c.Request.Context(), req.Platform, req.Token); err != nil {
		if errors.Is(err, errInvalidDevice) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "platform must be FCM or APNS and token must be non-empty"})
			return
		}
		h.log.Error("unregister device failed", "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to unregister device"})
		return
	}
	c.Status(http.StatusNoContent)
}
