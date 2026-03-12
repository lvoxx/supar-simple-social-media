# Starters — Module Reference

**Location:** `spring-services/starters/`  
**Naming convention:** `<technology>-starter`  
**Registration:** `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

Each starter is configured entirely through `application.yaml` in the consuming service.  
`@Bean` methods are written **only** for types that Spring Boot auto-configuration does not provide.

---

## postgres-starter

**Path:** `starters/postgres-starter`  
**Used by:** user-service · media-service · post-service · group-service  
**Spring auto-config leveraged:** `R2dbcAutoConfiguration`, `R2dbcRepositoriesAutoConfiguration`

> Schema is **not** initialised here. A K8S `Job` runs Flyway CLI before the service Pod starts.

### application.yaml (consuming service)

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    pool:
      initial-size: 5
      max-size: 20
      max-idle-time: 30m
      max-acquire-time: 5s
      validation-query: SELECT 1
```

### @Bean (not expressible in YAML)

```java
// ReactiveAuditorAware reads the current userId from Reactor Context.
// Spring has no YAML binding for this — it requires custom code.
@Bean
ReactiveAuditorAware<UUID> auditorAware() {
    return () -> ReactiveContextUtil.getCurrentUserId().defaultIfEmpty(SYSTEM_USER_ID);
}
```

---

## cassandra-starter

**Path:** `starters/cassandra-starter`  
**Used by:** comment-service · notification-service · private-message-service · message-notification-service  
**Spring auto-config leveraged:** `CassandraAutoConfiguration`, `CassandraReactiveRepositoriesAutoConfiguration`

> Keyspace and tables are **not** created here. A K8S `InitContainer` runs `cqlsh` scripts before the Pod starts.

### application.yaml (consuming service)

```yaml
spring:
  cassandra:
    contact-points: ${CASSANDRA_CONTACT_POINTS:localhost}
    port: ${CASSANDRA_PORT:9042}
    keyspace-name: ${CASSANDRA_KEYSPACE}
    local-datacenter: datacenter1
    username: ${CASSANDRA_USER:cassandra}
    password: ${CASSANDRA_PASSWORD:cassandra}
    schema-action: NONE        # schema managed by K8S — never by the service
    request:
      timeout: 10s
      consistency: LOCAL_QUORUM
      serial-consistency: LOCAL_SERIAL
      page-size: 20
    connection:
      connect-timeout: 5s
      init-query-timeout: 10s
```

No custom `@Bean` needed — Spring Boot auto-configuration is sufficient.

---

## redis-starter

**Path:** `starters/redis-starter`  
**Used by:** All Spring Boot services  
**Spring auto-config leveraged:** `RedisAutoConfiguration`, `RedisReactiveAutoConfiguration`, `CacheAutoConfiguration`

### application.yaml (consuming service)

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
  cache:
    type: redis
    redis:
      time-to-live: 300s
      cache-null-values: false
      key-prefix: "${spring.application.name}:"

redisson:
  single-server-config:
    address: redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    connection-pool-size: 16
    connection-minimum-idle-size: 4

sssm:
  rate-limit:
    enabled: true
    default-capacity: 100
    default-refill-tokens: 100
    default-refill-period: 1m
```

### @Bean (not expressible in YAML)

```java
// RedissonClient is not provided by Spring Boot auto-configuration.
@Bean
RedissonClient redissonClient(RedissonProperties props) {
    return Redisson.create(Config.fromYAML(props.toYaml()));
}

// RateLimiterService wraps Bucket4j + Redisson — no Spring equivalent.
@Bean
RateLimiterService rateLimiterService(RedissonClient client, RateLimitProperties props) {
    return new RateLimiterServiceImpl(client, props);
}
```

### Usage

```java
// @Cacheable / @CacheEvict — zero extra config
@Cacheable(value = "user:profile", key = "#userId")
public Mono<UserResponse> findById(String userId) { ... }

// Distributed lock via Redisson
RLock lock = redissonClient.getLock("lock:follow:" + userId + ":" + targetId);
Mono.fromCallable(() -> lock.tryLock(500, 5000, MILLISECONDS))
    .flatMap(ok -> ok ? doFollow().doFinally(s -> lock.unlock())
                      : Mono.error(new ConflictException("OPERATION_IN_PROGRESS")));
```

---

## kafka-starter

**Path:** `starters/kafka-starter`  
**Used by:** All services with Kafka integration  
**Spring auto-config leveraged:** `KafkaAutoConfiguration`

### application.yaml (consuming service)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1
        spring.json.add.type.headers: false
    consumer:
      group-id: ${spring.application.name}
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "io.github.lvoxx.*"
        isolation.level: read_committed
    listener:
      ack-mode: MANUAL_IMMEDIATE
      concurrency: 3

sssm:
  kafka:
    retry:
      max-attempts: 3
      initial-interval-ms: 200
      backoff-multiplier: 2.0
    dlt:
      enabled: true
      suffix: .DLT
```

### @Bean (not expressible in YAML)

```java
// ReactiveKafkaProducerTemplate is not auto-configured by Spring Boot.
@Bean
ReactiveKafkaProducerTemplate<String, Object> reactiveKafkaProducerTemplate(
        KafkaProperties props) {
    return new ReactiveKafkaProducerTemplate<>(
        SenderOptions.create(props.buildProducerProperties()));
}
```

### Standard event envelope

```java
public record KafkaEventEnvelope<T>(
    String  eventId,          // ULID
    String  eventType,        // "post.created"
    String  version,          // "1"
    Instant timestamp,
    String  producerService,
    String  correlationId,
    T       payload
) {}
```

---

## elasticsearch-starter

**Path:** `starters/elasticsearch-starter`  
**Used by:** search-service  
**Spring auto-config leveraged:** `ElasticsearchRestClientAutoConfiguration`, `ReactiveElasticsearchRepositoriesAutoConfiguration`

> Index mappings are **not** created here. A K8S `Job` calls the ES REST API before the Pod starts.

### application.yaml (consuming service)

```yaml
spring:
  elasticsearch:
    uris: ${ES_URIS:http://localhost:9200}
    username: ${ES_USERNAME:elastic}
    password: ${ES_PASSWORD:}
    connection-timeout: 5s
    socket-timeout: 30s
```

### @Bean (not expressible in YAML)

```java
// Alias rotation and bulk-flush helpers — no Spring equivalent.
@Bean
ElasticsearchIndexManager indexManager(ReactiveElasticsearchClient client) {
    return new ElasticsearchIndexManager(client);
}
```

---

## metrics-starter

**Path:** `starters/metrics-starter`  
**Used by:** All services  
**Spring auto-config leveraged:** `MetricsAutoConfiguration`, `TracingAutoConfiguration`, `ZipkinAutoConfiguration`, `PrometheusAutoConfiguration`

### application.yaml (consuming service)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when_authorized
  tracing:
    sampling:
      probability: ${TRACING_SAMPLE_RATE:0.1}
  zipkin:
    tracing:
      endpoint: ${ZIPKIN_ENDPOINT:http://zipkin:9411/api/v2/spans}
  metrics:
    tags:
      service: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true
      percentiles:
        http.server.requests: 0.5,0.95,0.99

logging:
  pattern:
    console: >-
      {"ts":"%d{yyyy-MM-dd'T'HH:mm:ss.SSSZ}","lvl":"%level",
       "svc":"${spring.application.name}","traceId":"%X{traceId}",
       "spanId":"%X{spanId}","userId":"%X{userId}","msg":"%msg"}%n
```

### @Bean (not expressible in YAML)

```java
// Injects traceId / spanId / userId into every log line via MDC.
// Spring ships no WebFilter for this.
@Bean
@Order(-90)
MdcPropagationWebFilter mdcPropagationWebFilter() {
    return new MdcPropagationWebFilter();
}
```

---

## websocket-starter

**Path:** `starters/websocket-starter`  
**Used by:** notification-service · private-message-service  
**Spring auto-config leveraged:** `WebFluxAutoConfiguration`

### application.yaml (consuming service)

```yaml
sssm:
  websocket:
    allowed-origins: ${WS_ALLOWED_ORIGINS:*}
    heartbeat-interval: 25s
    connection-timeout: 60s
    max-text-message-size: 65536
```

### @Bean (not expressible in YAML)

```java
// WebSocketHandlerAdapter and handler URL mapping cannot be configured via YAML.
@Bean WebSocketHandlerAdapter webSocketHandlerAdapter() {
    return new WebSocketHandlerAdapter();
}

@Bean
HandlerMapping wsHandlerMapping(Map<String, WebSocketHandler> handlers,
                                WebSocketProperties props) {
    SimpleUrlHandlerMapping m = new SimpleUrlHandlerMapping();
    m.setUrlMap(handlers);
    m.setOrder(-1);
    return m;
}
```

---

## security-starter

**Path:** `starters/security-starter`  
**Used by:** All services  
**Purpose:** Extract JWT claims from gateway-forwarded headers. No JWT validation — that is the gateway's job.  
**Spring auto-config leveraged:** None (no Spring Security auth chain).

### application.yaml (consuming service)

```yaml
sssm:
  security:
    user-id-header: X-User-Id
    roles-header:   X-User-Roles
    ip-header:      X-Forwarded-For
    anonymous-paths:
      - /actuator/**
      - /v3/api-docs/**
```

### @Bean (not expressible in YAML)

```java
// Header extraction WebFilter and argument resolver are custom — no Spring equivalent.
@Bean @Order(-100)
ClaimExtractionWebFilter claimExtractionWebFilter(SecurityProperties props) {
    return new ClaimExtractionWebFilter(props);
}

@Bean
CurrentUserArgumentResolver currentUserArgumentResolver() {
    return new CurrentUserArgumentResolver();
}
```

### How it works

```
K8S Gateway validates JWT → injects headers:
  X-User-Id:       "01HXZ..."
  X-User-Roles:    "USER,MODERATOR"
  X-Forwarded-For: "1.2.3.4"

security-starter WebFilter (order -100):
  1. Reads headers
  2. Builds UserPrincipal
  3. Writes into Reactor Context + MDC

Handler:
  Mono<Response> getMe(@CurrentUser UserPrincipal user) { ... }
  // or
  ReactiveContextUtil.getCurrentUserId().flatMap(id -> ...)
```

---

## grpc-starter

**Path:** `starters/grpc-starter`  
**Used by:** All Spring Boot services (server role: services with gRPC endpoints; client role: services that call others)  
**Purpose:** Configure `grpc-server-spring-boot-starter` and `grpc-client-spring-boot-starter` from `net.devh`, register global interceptors (auth, metrics, tracing), and provide a `@GrpcAdvice` exception handler.

> Proto stubs are generated from `spring-services/proto/` by `protobuf-maven-plugin`.  
> Each service depends on `:proto` in its `pom.xml`.

### application.yaml (consuming service)

```yaml
# Server — every service exposes gRPC on port 9090
grpc:
  server:
    port: 9090
    security:
      enabled: false   # mTLS handled by Istio sidecar in prod; plaintext in dev

# Client — one entry per upstream service this service calls
# Only add entries for services this specific service actually calls.
# Example below is for post-service, which only calls user-service.
  client:
    user-service:
      address:          static://user-service.sssm.svc.cluster.local:9090
      negotiation-type: plaintext
      keep-alive-time:    30s
      keep-alive-timeout: 10s
      enable-keep-alive:  true
      deadline-duration:  3s     # per-call deadline — fail fast
```

### @Bean (not expressible in YAML)

```java
// grpc-starter/src/main/java/io/github/lvoxx/starter/grpc/GrpcStarterAutoConfiguration.java

@AutoConfiguration
@ConditionalOnClass(GrpcServerFactory.class)
public class GrpcStarterAutoConfiguration {

    // Propagate X-User-Id / X-User-Roles into gRPC calls originating from service code
    // (when a gRPC server method itself calls another gRPC service).
    @Bean
    public ClientInterceptor userContextPropagationInterceptor() {
        return new UserContextPropagationInterceptor();
    }

    // Attach traceId / spanId to every gRPC call for Zipkin correlation.
    // Spring Boot ships no auto-configured gRPC tracing interceptor.
    @Bean
    public ServerInterceptor tracingServerInterceptor(Tracer tracer) {
        return new TracingServerInterceptor(tracer);
    }

    // Prometheus metrics for gRPC: grpc_server_handled_total, grpc_server_handling_seconds
    @Bean
    public ServerInterceptor metricsServerInterceptor(MeterRegistry registry) {
        return new MetricsServerInterceptor(registry);
    }

    // Map domain exceptions to gRPC Status codes uniformly across all services.
    @Bean
    public GrpcExceptionHandler grpcExceptionHandler() {
        return new GlobalGrpcExceptionHandler();
    }
}
```

### GlobalGrpcExceptionHandler

```java
// grpc-starter — shared exception → Status mapping

@GrpcAdvice
public class GlobalGrpcExceptionHandler {

    @GrpcExceptionHandler(EntityNotFoundException.class)
    public Status handleNotFound(EntityNotFoundException ex) {
        return Status.NOT_FOUND.withDescription(ex.getMessage()).withCause(ex);
    }

    @GrpcExceptionHandler(ForbiddenException.class)
    public Status handleForbidden(ForbiddenException ex) {
        return Status.PERMISSION_DENIED.withDescription(ex.getMessage());
    }

    @GrpcExceptionHandler(ValidationException.class)
    public Status handleValidation(ValidationException ex) {
        return Status.INVALID_ARGUMENT.withDescription(ex.getMessage());
    }

    @GrpcExceptionHandler(ConflictException.class)
    public Status handleConflict(ConflictException ex) {
        return Status.ALREADY_EXISTS.withDescription(ex.getMessage());
    }
}
```

### Server implementation pattern

```java
// Implement the generated ReactorXxxServiceGrpc.XxxServiceImplBase in the owning service.

@GrpcService   // registers with gRPC server automatically
@RequiredArgsConstructor
public class UserGrpcService extends ReactorUserServiceGrpc.UserServiceImplBase {

    private final UserRepository userRepository;
    private final BlockRepository blockRepository;

    @Override
    public Mono<UserResponse> findUserById(Mono<FindUserByIdRequest> request) {
        return request.flatMap(req ->
            userRepository.findById(req.getUserId())
                .switchIfEmpty(Mono.error(new EntityNotFoundException("User: " + req.getUserId())))
                .map(this::toProto)
        );
    }

    @Override
    public Mono<CheckUserBlockedResponse> checkUserBlocked(Mono<CheckUserBlockedRequest> req) {
        return req.flatMap(r ->
            blockRepository.existsByBlockerIdAndBlockedId(r.getBlockerId(), r.getBlockedId())
                .map(blocked -> CheckUserBlockedResponse.newBuilder().setIsBlocked(blocked).build())
        );
    }

    private UserResponse toProto(UserEntity u) {
        return UserResponse.newBuilder()
            .setUserId(u.getId()).setUsername(u.getUsername())
            .setDisplayName(u.getDisplayName())
            .setAvatarUrl(u.getAvatarUrl() != null ? u.getAvatarUrl() : "")
            .setIsVerified(u.isVerified()).setIsPrivate(u.isPrivate())
            .setIsActive(!u.isDeleted())
            .build();
    }
}
```

### Client call pattern

```java
// Inject stub with @GrpcClient — net.devh manages the channel lifecycle.

@Service
@RequiredArgsConstructor
public class PostService {

    @GrpcClient("user-service")
    private ReactorUserServiceGrpc.ReactorUserServiceStub userStub;

    private Mono<Void> validateAuthor(String userId) {
        return userStub
            .withDeadlineAfter(3, TimeUnit.SECONDS)
            .findUserById(FindUserByIdRequest.newBuilder().setUserId(userId).build())
            .filter(UserResponse::getIsActive)
            .switchIfEmpty(Mono.error(new UserNotFoundException(userId)))
            .then();
    }
}
```

### pom.xml (parent BOM manages versions)

```xml
<!-- grpc-starter/pom.xml -->
<dependencies>
  <!-- gRPC server + client auto-configuration for Spring Boot -->
  <dependency>
    <groupId>net.devh</groupId>
    <artifactId>grpc-server-spring-boot-starter</artifactId>
  </dependency>
  <dependency>
    <groupId>net.devh</groupId>
    <artifactId>grpc-client-spring-boot-starter</artifactId>
  </dependency>
  <!-- Reactor-gRPC bridge — enables Mono/Flux return types in @GrpcService -->
  <dependency>
    <groupId>com.salesforce.servicelibs</groupId>
    <artifactId>reactor-grpc-stub</artifactId>
  </dependency>
</dependencies>

<!-- In consuming service pom.xml, also add the :proto module -->
<!-- <dependency>
       <groupId>io.github.lvoxx</groupId>
       <artifactId>proto</artifactId>
     </dependency> -->
```

---

## Dependency matrix

| Service | postgres | cassandra | redis | kafka | elasticsearch | metrics | websocket | security | **grpc** |
|---------|:--------:|:---------:|:-----:|:-----:|:-------------:|:-------:|:---------:|:--------:|:--------:|
| user-service | ✅ | | ✅ | ✅ | | ✅ | | ✅ | ✅ |
| media-service | ✅ | | ✅ | ✅ | | ✅ | | ✅ | ✅ |
| post-service | ✅ | | ✅ | ✅ | | ✅ | | ✅ | ✅ |
| comment-service | | ✅ | ✅ | ✅ | | ✅ | | ✅ | ✅ |
| notification-service | | ✅ | ✅ | ✅ | | ✅ | ✅ | ✅ | ✅ |
| search-service | | | ✅ | ✅ | ✅ | ✅ | | ✅ | ✅ |
| group-service | ✅ | | ✅ | ✅ | | ✅ | | ✅ | ✅ |
| private-message-service | | ✅ | ✅ | ✅ | | ✅ | ✅ | ✅ | ✅ |
| message-notification-service | | ✅ | ✅ | ✅ | | ✅ | | ✅ | ✅ |
