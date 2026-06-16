package httpx

import (
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// Identity headers injected by the gateway sidecar. The sidecar has already validated the Keycloak
// access token; downstream services TRUST these headers because the network topology guarantees a
// service is only reachable through the gateway (see ADR-0003). Services NEVER decode JWTs — this
// mirrors the Java GatewayAuthenticationFilter so the Go and Spring fleets agree on the contract.
const (
	HeaderAuthSubject  = "X-Auth-Subject"
	HeaderAuthUsername = "X-Auth-Username"
	HeaderAuthRoles    = "X-Auth-Roles"
)

// AuthSubject returns the authenticated caller's UUID parsed from X-Auth-Subject. ok is false when
// the header is absent or malformed, in which case the request is treated as unauthenticated — the
// handler decides whether that is allowed (public read) or a 401 (protected route).
func AuthSubject(c *gin.Context) (uuid.UUID, bool) {
	raw := c.GetHeader(HeaderAuthSubject)
	if raw == "" {
		return uuid.Nil, false
	}
	id, err := uuid.Parse(raw)
	if err != nil {
		return uuid.Nil, false
	}
	return id, true
}
