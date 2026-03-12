# kafka-starter

Auto-configures a reactive Kafka producer and Avro-typed consumer factory using Confluent Schema Registry.

## What it provides

| Bean | Type |
|------|------|
| `ReactiveKafkaProducerTemplate<String, SpecificRecord>` | Idempotent Avro producer |
| `ConcurrentKafkaListenerContainerFactory` | MANUAL_IMMEDIATE ACK, 3-thread concurrency |
| `ConsumerFactory` | `KafkaAvroDeserializer` with `specific.avro.reader=true` |

## Avro topic naming

Uses `TopicRecordNameStrategy` so each topic+record combination registers its own schema subject:
`<topic>-<fully-qualified-record-name>-value`

## Default configuration (`application-kafka.yaml`)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    properties:
      schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
      specific.avro.reader: true
    consumer:
      group-id: ${spring.application.name}
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener:
      ack-mode: MANUAL_IMMEDIATE
      concurrency: 3
```

## Idempotent producer

`enable.idempotence=true`, `acks=all`, `max.in.flight.requests.per.connection=1`
→ Exactly-once delivery guarantee at the producer level.

## Consumer usage

```java
@KafkaListener(topics = KafkaTopics.POST_CREATED, groupId = "${spring.application.name}")
public void onPostCreated(@Payload PostCreatedEvent event, Acknowledgment ack) {
    // process...
    ack.acknowledge();
}
```

## Dependency

```xml
<dependency>
    <groupId>io.github.lvoxx</groupId>
    <artifactId>kafka-starter</artifactId>
</dependency>
```
