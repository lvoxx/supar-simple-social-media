# common-core

**Type:** Shared library (not a runnable service)  
**Path:** `spring-services/common/common-core`  
**Packaging:** JAR imported by all Spring Boot services  

---

## Purpose

Provides shared infrastructure code: exception hierarchy, response wrappers, base entities, enums, ID generation, security context, and reactive validation.  
Contains **no business logic**.

---

## Maven dependency

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>common-core</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## Package structure

```
io.github.lvoxx.common_core
├── exception/
├── handler/
├── message/
├── model/
├── enums/
├── security/
├── util/
└── validation/
```

---

## exception/

### `BusinessException` (base)

```java
public class BusinessException extends RuntimeException {
    private final String errorCode;
    private final Object[] args;
    public BusinessException(String errorCode, Object... args) { ... }
}
```

### Concrete subclasses

| Class | HTTP | When |
|-------|------|------|
| `ResourceNotFoundException` | 404 | Entity not found |
| `ConflictException` | 409 | Duplicate state (already liked, already following) |
| `ForbiddenException` | 403 | Missing permission |
| `ValidationException` | 422 | Invalid input |
| `ExternalServiceException` | 502 | Upstream service failed |
| `RateLimitExceededException` | 429 | Rate limit exceeded |

```java
throw new ResourceNotFoundException(MessageKeys.USER_NOT_FOUND, userId);
throw new ConflictException(MessageKeys.ALREADY_FOLLOWING, targetId);
```

---

## handler/

### `GlobalErrorWebExceptionHandler`

Reactive `WebExceptionHandler` at order `-2`. Catches all exceptions, converts to `ApiResponse<Void>` with `ErrorResponse`.

```java
public record ErrorResponse(String code, String message, List<String> details, Instant timestamp) {}
```

---

## message/

### `MessageKeys`

```java
public final class MessageKeys {
    // User
    public static final String USER_NOT_FOUND        = "USER_NOT_FOUND";
    public static final String USERNAME_TAKEN        = "USERNAME_TAKEN";
    public static final String ALREADY_FOLLOWING     = "ALREADY_FOLLOWING";
    public static final String NOT_FOLLOWING         = "NOT_FOLLOWING";
    // Post
    public static final String POST_NOT_FOUND        = "POST_NOT_FOUND";
    public static final String POST_CONTENT_REJECTED = "POST_CONTENT_REJECTED";
    public static final String POST_ALREADY_LIKED    = "POST_ALREADY_LIKED";
    // Media
    public static final String MEDIA_NOT_FOUND       = "MEDIA_NOT_FOUND";
    public static final String MEDIA_PROCESSING      = "MEDIA_STILL_PROCESSING";
    public static final String MEDIA_REJECTED        = "MEDIA_CONTENT_REJECTED";
    public static final String FILE_TOO_LARGE        = "FILE_SIZE_EXCEEDS_LIMIT";
    public static final String INVALID_FILE_TYPE     = "INVALID_FILE_TYPE";
    // Group
    public static final String GROUP_NOT_FOUND       = "GROUP_NOT_FOUND";
    public static final String ALREADY_MEMBER        = "ALREADY_GROUP_MEMBER";
    public static final String NOT_MEMBER            = "NOT_GROUP_MEMBER";
    public static final String INSUFFICIENT_ROLE     = "INSUFFICIENT_GROUP_ROLE";
    public static final String OWNER_CANNOT_LEAVE    = "OWNER_CANNOT_LEAVE_GROUP";
    public static final String MAX_PINS_REACHED      = "MAX_PINNED_POSTS_REACHED";
    // Message
    public static final String CONV_NOT_FOUND        = "CONVERSATION_NOT_FOUND";
    public static final String MSG_NOT_FOUND         = "MESSAGE_NOT_FOUND";
    public static final String MSG_EDIT_EXPIRED      = "MESSAGE_EDIT_TIME_EXPIRED";
    public static final String DM_NOT_ALLOWED        = "DIRECT_MESSAGE_NOT_ALLOWED";
    // Generic
    public static final String RATE_LIMIT_EXCEEDED   = "RATE_LIMIT_EXCEEDED";
    public static final String FORBIDDEN             = "ACCESS_FORBIDDEN";
    public static final String INTERNAL_ERROR        = "INTERNAL_SERVER_ERROR";
    public static final String EXTERNAL_SERVICE_DOWN = "EXTERNAL_SERVICE_UNAVAILABLE";
}
```

---

## model/

### `ApiResponse<T>`

```java
public record ApiResponse<T>(boolean success, T data, ErrorResponse error, ApiMeta meta) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, ApiMeta.now());
    }
    public static ApiResponse<Void> error(ErrorResponse error) {
        return new ApiResponse<>(false, null, error, ApiMeta.now());
    }
}

public record ApiMeta(String requestId, Instant timestamp, String version) {
    public static ApiMeta now() {
        return new ApiMeta(UlidGenerator.generate(), Instant.now(), "v1");
    }
}
```

### `PageResponse<T>`

```java
public record PageResponse<T>(
    List<T> items,
    String  nextCursor,   // null = no more pages
    boolean hasMore,
    Long    total         // null for Cassandra
) {}
```

### `AuditableEntity`

```java
@Data
public abstract class AuditableEntity {
    @CreatedDate       private Instant createdAt;
    @LastModifiedDate  private Instant updatedAt;
    @CreatedBy         private UUID    createdBy;
    @LastModifiedBy    private UUID    updatedBy;
}
```

### `SoftDeletableEntity`

```java
@Data
public abstract class SoftDeletableEntity extends AuditableEntity {
    private Boolean isDeleted = false;
    private Instant deletedAt;
    private UUID    deletedBy;
}
```

---

## enums/

```java
public enum UserRole         { USER, MODERATOR, ADMIN, SYSTEM }
public enum ContentStatus    { ACTIVE, HIDDEN, FLAGGED, PENDING_REVIEW, DELETED }
public enum MediaType        { IMAGE, VIDEO, AUDIO, DOCUMENT, STICKER }
public enum NotificationType { LIKE, COMMENT, FOLLOW, MENTION, REPOST, SYSTEM,
                               GROUP_JOIN, GROUP_ROLE_CHANGED,
                               MESSAGE_REACTION, CONVERSATION_CREATED }
public enum GroupMemberRole  { OWNER, ADMIN, MODERATOR, MEMBER }
public enum GroupVisibility  { PUBLIC, PRIVATE, INVITE_ONLY }
public enum MessageStatus    { SENT, DELIVERED, READ, FAILED, DELETED }
public enum ConversationType { DIRECT, GROUP_CHAT, GROUP_CHANNEL }
```

---

## security/

### `UserPrincipal`

```java
public record UserPrincipal(UUID userId, String username, Set<UserRole> roles, String ip) {
    public boolean isAdmin()     { return roles.contains(ADMIN); }
    public boolean isModerator() { return isAdmin() || roles.contains(MODERATOR); }
    public boolean hasRole(UserRole r) { return roles.contains(r); }
}
```

### `@CurrentUser`

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {}

// Usage
Mono<ApiResponse<UserResponse>> getMe(@CurrentUser UserPrincipal user) { ... }
```

---

## util/

```java
public final class UlidGenerator {
    public static String generate()       { ... }  // "01HXZ..."
    public static UUID   generateAsUUID() { ... }
}

public final class ReactiveContextUtil {
    public static Mono<UserPrincipal> getCurrentUser()   { ... }
    public static Mono<UUID>          getCurrentUserId() { ... }
}

public final class SlugUtil {
    public static String  toSlug(String text)      { ... }  // "Hello World" → "hello-world"
    public static boolean isValidSlug(String slug) { ... }
}
```

---

## validation/

```java
@Component
public class ReactiveValidator {
    public <T> Mono<T> validate(T object) {
        Set<ConstraintViolation<T>> v = validator.validate(object);
        return v.isEmpty() ? Mono.just(object)
                           : Mono.error(new ValidationException(buildMessage(v)));
    }
}
```

---

## Auto-configuration

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports:
  io.github.lvoxx.common_core.handler.GlobalErrorWebExceptionHandlerAutoConfig
  io.github.lvoxx.common_core.validation.ReactiveValidatorAutoConfig
```

No additional setup needed in consuming services.
