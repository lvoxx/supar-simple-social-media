package main

import (
	"errors"
	"log/slog"
	"net/http"
	"strconv"

	"github.com/coder/websocket"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/lvoxx/sssm/go/common/httpx"
)

// Handler mounts the messaging-service HTTP API: a REST surface for the conversation/message history
// and a WebSocket endpoint for the live bidirectional DM stream. Identity comes from the gateway
// sidecar (X-Auth-Subject); every route is per-caller, so a missing subject is always a 401.
type Handler struct {
	svc             *MessagingService
	session         *Session
	log             *slog.Logger
	maxMessageBytes int
}

func NewHandler(svc *MessagingService, session *Session, maxMessageBytes int, log *slog.Logger) *Handler {
	return &Handler{svc: svc, session: session, log: log, maxMessageBytes: maxMessageBytes}
}

// RegisterRoutes mounts the messaging-service HTTP API on the given router.
func (h *Handler) RegisterRoutes(r gin.IRouter) {
	g := r.Group("/api/v1/messages")
	g.GET("/conversations", h.handleListConversations)
	g.GET("/conversations/:id", h.handleListMessages)
	g.POST("", h.handleSend)
	g.GET("/stream", h.handleStream)
}

func (h *Handler) handleListConversations(c *gin.Context) {
	userID, ok := httpx.AuthSubject(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	limit, ok := parseLimit(c)
	if !ok {
		return
	}
	page, err := h.svc.ListConversations(c.Request.Context(), userID, c.Query("cursor"), limit)
	if err != nil {
		if errors.Is(err, errInvalidCursor) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid cursor"})
			return
		}
		h.log.Error("list conversations failed", "user_id", userID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load conversations"})
		return
	}
	c.JSON(http.StatusOK, page)
}

func (h *Handler) handleListMessages(c *gin.Context) {
	userID, ok := httpx.AuthSubject(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	conversationID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid conversation id"})
		return
	}
	limit, ok := parseLimit(c)
	if !ok {
		return
	}
	page, err := h.svc.ListMessages(c.Request.Context(), conversationID, userID, c.Query("cursor"), limit)
	if err != nil {
		if errors.Is(err, errInvalidCursor) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid cursor"})
			return
		}
		h.log.Error("list messages failed", "user_id", userID, "conversation_id", conversationID, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load messages"})
		return
	}
	c.JSON(http.StatusOK, page)
}

// sendRequest is the body for the REST send endpoint (the WebSocket path uses clientFrame). Both reach
// the same MessagingService.SendMessage.
type sendRequest struct {
	RecipientID string `json:"recipientId"`
	Body        string `json:"body"`
}

func (h *Handler) handleSend(c *gin.Context) {
	userID, ok := httpx.AuthSubject(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	var req sendRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
		return
	}
	recipientID, err := uuid.Parse(req.RecipientID)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid recipientId"})
		return
	}
	msg, err := h.svc.SendMessage(c.Request.Context(), userID, recipientID, req.Body)
	if err != nil {
		switch {
		case errors.Is(err, errSelfMessage), errors.Is(err, errEmptyBody), errors.Is(err, errBodyTooLong):
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		default:
			h.log.Error("send message failed", "user_id", userID, "err", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to send message"})
		}
		return
	}
	c.JSON(http.StatusCreated, msg)
}

// handleStream upgrades the request to a WebSocket and hands it to a Session for the lifetime of the
// connection. Auth is checked BEFORE the upgrade so an unauthenticated client gets a clean 401 rather
// than a half-open socket.
func (h *Handler) handleStream(c *gin.Context) {
	userID, ok := httpx.AuthSubject(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
		return
	}
	conn, err := websocket.Accept(c.Writer, c.Request, nil)
	if err != nil {
		h.log.Warn("websocket upgrade failed", "user_id", userID, "err", err)
		return
	}
	conn.SetReadLimit(int64(h.maxMessageBytes) + 1024) // body cap plus envelope slack
	h.session.Serve(c.Request.Context(), userID, coderConn{c: conn})
}

// parseLimit reads ?limit as a non-negative int, writing a 400 and returning ok=false on a bad value.
// An absent limit yields 0 (the service then applies its default).
func parseLimit(c *gin.Context) (int, bool) {
	raw := c.Query("limit")
	if raw == "" {
		return 0, true
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "limit must be a non-negative integer"})
		return 0, false
	}
	return n, true
}
