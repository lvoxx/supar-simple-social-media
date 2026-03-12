# elasticsearch-starter

Auto-configures the Spring Data Reactive Elasticsearch client.

## What it provides

- `ReactiveElasticsearchClient` configured for the cluster URI
- `ReactiveElasticsearchTemplate` for template-style queries
- Reactive repository support (`ReactiveElasticsearchRepository`)

## Default configuration (`application-elasticsearch.yaml`)

```yaml
spring:
  elasticsearch:
    uris: ${ES_URIS:http://localhost:9200}
    socket-timeout: 30s
    connection-timeout: 5s
```

## Service-level override (search-service only)

```yaml
spring:
  elasticsearch:
    username: ${ES_USERNAME:elastic}
    password: ${ES_PASSWORD:}
```

## Index mappings

Index creation and field mapping are managed by init jobs in
`infrastructure/k8s/infra/jobs/elasticsearch-index-init.yaml`.

Mapping files: `infrastructure/k8s/db-init/search-service/mappings/`

## Dependency

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>elasticsearch-starter</artifactId>
</dependency>
```
