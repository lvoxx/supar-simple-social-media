# metrics-starter

Auto-configures Micrometer metrics, distributed tracing with Zipkin, and MDC trace-ID injection.

## What it provides

| Bean | Purpose |
|------|---------|
| `MeterRegistry` | Micrometer metrics registry (Prometheus-compatible) |
| `TracingMdcFilter` | WebFilter that copies `traceId`/`spanId` into MDC for structured logging |
| Management endpoints | `/actuator/health`, `/actuator/prometheus` exposed |

## Default configuration (`application-metrics.yaml`)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  tracing:
    sampling:
      probability: 1.0
  zipkin:
    tracing:
      endpoint: ${ZIPKIN_ENDPOINT:http://localhost:9411/api/v2/spans}
```

## Trace propagation

Every incoming request gets a `traceId` injected into the Reactor context.
The `TracingMdcFilter` propagates it to MDC so every log line contains:

```
[traceId=abc123 spanId=def456] INFO  UserServiceImpl - User found userId=...
```

## Prometheus scraping

`GET /actuator/prometheus` — compatible with any Prometheus scrape config.

## Dependency

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>metrics-starter</artifactId>
</dependency>
```
