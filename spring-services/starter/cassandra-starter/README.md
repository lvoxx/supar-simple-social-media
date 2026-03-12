# cassandra-starter

Auto-configures the Spring Data Reactive Cassandra driver.

## What it provides

- `ReactiveCassandraTemplate` and reactive repository support
- Default contact-points, port, local datacenter, and timeout settings
- Schema validation disabled (`schema-action: NONE`) — managed by init CQL scripts

## Default configuration (`application-cassandra.yaml`)

```yaml
spring:
  cassandra:
    contact-points: ${CASSANDRA_CONTACT_POINTS:localhost}
    port: ${CASSANDRA_PORT:9042}
    local-datacenter: datacenter1
    schema-action: NONE
    request:
      timeout: 5s
    connection:
      connect-timeout: 5s
      init-query-timeout: 5s
```

## Service-level override (only keyspace differs per service)

```yaml
spring:
  cassandra:
    keyspace-name: sssm_comments
```

## Dependency

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>cassandra-starter</artifactId>
</dependency>
```
