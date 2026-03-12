# security-starter

Propagates Keycloak JWT claims from API Gateway headers into the Reactor context as a `UserPrincipal`.

## Architecture

The API Gateway validates JWTs with Keycloak and forwards two headers to backend services:

```
X-User-Id:    <UUID>
X-User-Roles: ROLE_USER,ROLE_ADMIN
```

`UserPrincipalFilter` reads these headers, constructs a `UserPrincipal`, and stores it in the Reactor subscriber context.
No JWT re-validation happens in microservices — trust is placed on the internal network.

## What it provides

| Bean | Purpose |
|------|---------|
| `UserPrincipalFilter` | WebFilter — reads `X-User-Id`/`X-User-Roles`, stores `UserPrincipal` in context |
| `CurrentUserArgumentResolver` | Resolves `@CurrentUser UserPrincipal` method params in controllers |

## Accessing the current user

```java
// Option 1: via ReactiveContextUtil (in handlers/services)
ReactiveContextUtil.getCurrentUser()
    .flatMap(principal -> userService.getById(principal.userId()))

// Option 2: @CurrentUser annotation (in handler method params)
public Mono<ServerResponse> getMe(ServerRequest req, @CurrentUser UserPrincipal principal) {
    return userService.getById(principal.userId())...;
}
```

## Anonymous paths

Configure paths that skip authentication:

```yaml
sssm:
  security:
    anonymous-paths:
      - /actuator/**
      - /v3/api-docs/**
      - /swagger-ui/**
```

## Default configuration (`application-security.yaml`)

```yaml
sssm:
  security:
    user-id-header:    X-User-Id
    user-roles-header: X-User-Roles
    anonymous-paths:
      - /actuator/health
      - /actuator/info
```

## Dependency

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>security-starter</artifactId>
</dependency>
```
