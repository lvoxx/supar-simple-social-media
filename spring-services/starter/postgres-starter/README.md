# postgres-starter

Auto-configures a reactive R2DBC connection pool for PostgreSQL.

## What it provides

- `ConnectionPool` (R2DBC) with sane defaults: `initialSize=5`, `maxSize=20`, `maxIdleTime=30m`, `validationQuery=SELECT 1`
- Flyway migration applied at startup via a `ConnectionFactory` wrapper
- `@ConditionalOnMissingBean` on every bean — full override capability

## Default configuration (`application-postgres.yaml`)

```yaml
spring:
  r2dbc:
    pool:
      initial-size: 5
      max-size: 20
      max-idle-time: 30m
      validation-query: SELECT 1
```

## Service-level overrides (only specify differences)

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:mydb}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
```

## Dependency

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>postgres-starter</artifactId>
</dependency>
```
