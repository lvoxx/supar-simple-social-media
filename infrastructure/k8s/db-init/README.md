# DB Init Jobs

Chạy tất cả migrations:
```bash
kubectl apply -k infrastructure/k8s/db-init/
kubectl wait --for=condition=complete job --all -n sssm --timeout=600s
```

Hoặc từng loại riêng:
```bash
# PostgreSQL only
kubectl apply -f user-service/db-init-job.yaml -n sssm
kubectl apply -f post-service/db-init-job.yaml -n sssm
kubectl apply -f media-service/db-init-job.yaml -n sssm
kubectl apply -f group-service/db-init-job.yaml -n sssm

# Cassandra only
kubectl apply -f comment-service/db-init-job.yaml -n sssm
kubectl apply -f notification-service/db-init-job.yaml -n sssm
kubectl apply -f message-notification-service/db-init-job.yaml -n sssm
kubectl apply -f private-message-service/db-init-job.yaml -n sssm

# OpenSearch only
kubectl apply -f search-service/db-init-job.yaml -n sssm
```

| Service | DB Type | Migration Tool | Keyspace/DB/Index |
|---------|---------|----------------|-------------------|
| user-service | PostgreSQL | Flyway V1-V5 | sssm_users |
| post-service | PostgreSQL | Flyway V1 | sssm_posts |
| media-service | PostgreSQL | Flyway V1 | sssm_media |
| group-service | PostgreSQL | Flyway V1 | sssm_groups |
| comment-service | Cassandra | cqlsh | sssm_comments |
| notification-service | Cassandra | cqlsh | sssm_notifications |
| message-notification-service | Cassandra | cqlsh | sssm_msg_notifications |
| private-message-service | Cassandra | cqlsh | sssm_messages |
| search-service | OpenSearch | curl REST API | posts_v1, users_v1 |
