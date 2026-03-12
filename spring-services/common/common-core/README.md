# common-core

Shared domain primitives imported by every service and starter.
Zero Spring-Boot-specific runtime required — all classes work as plain library code.

---

## Contents

### Exceptions

| Class | HTTP mapping | Usage |
|-------|-------------|-------|
| `ResourceNotFoundException` | 404 | Entity not found by ID / username |
| `ConflictException` | 409 | Duplicate follow, already member, etc. |
| `ValidationException` | 422 | Bean-validation failures |
| `ForbiddenException` | 403 | Caller lacks permission |
| `RateLimitExceededException` | 429 | Token-bucket exhausted |
| `ExternalServiceException` | 502 | CDN / push notification failures |

All extend `BusinessException` which carries a `messageKey` (for i18n) and optional `context` args.

### Error Handling

`GlobalErrorWebExceptionHandler` — registered as `@AutoConfiguration` via `GlobalErrorWebExceptionHandlerAutoConfig`.
Maps every `BusinessException` subclass to the correct HTTP status and wraps the response in `ApiResponse.error(...)`.

### Models

```java
ApiResponse<T>    // { success, data, errorCode, message, timestamp }
PageResponse<T>   // { items, nextCursor, total }
AuditableEntity   // createdAt, updatedAt (@CreatedDate / @LastModifiedDate)
SoftDeletableEntity // extends AuditableEntity + isDeleted, deletedAt, deletedBy
```

### Security Context

```java
UserPrincipal      // record(UUID userId, String username, List<String> roles)
ReactiveContextUtil.getCurrentUser()  // Mono<UserPrincipal> from Reactor context
@CurrentUser       // method parameter annotation resolved by security-starter
```

### Enums

`UserRole`, `ContentStatus`, `GroupVisibility`, `GroupMemberRole`,
`MessageStatus`, `NotificationType`, `MediaType`, `ConversationType`

### Utilities

| Class | Purpose |
|-------|---------|
| `UlidGenerator` | Thread-safe ULID → UUID / String |
| `SlugUtil` | `toSlug("My Group!")` → `"my-group"` |
| `ReactiveValidator` | Wraps Jakarta `Validator` in reactive `Mono.error` pipeline |

### Message Keys

`MessageKeys` — i18n message-code constants (`USER_NOT_FOUND`, `POST_ALREADY_LIKED`, …)

---

## Usage

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>common-core</artifactId>
</dependency>
```

```java
// Throw a typed exception — GlobalErrorWebExceptionHandler handles status mapping
.switchIfEmpty(Mono.error(new ResourceNotFoundException(MessageKeys.USER_NOT_FOUND, userId)))

// Access current user from Reactor context
ReactiveContextUtil.getCurrentUser()
    .flatMap(principal -> userService.getById(principal.userId()))
```
