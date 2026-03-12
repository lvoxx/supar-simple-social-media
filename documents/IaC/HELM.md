# Helm — Chart Reference

**Helm version:** 3.15+  
**Chart API version:** `v2`  
**Release strategy:** ArgoCD manages all releases in prod/staging. Direct `helm upgrade` used in dev only.

---

## Chart inventory

| Chart | Namespace | Contents |
|-------|-----------|----------|
| `sssm-services` | `sssm` | All 13 microservices (each as a sub-chart) |
| `sssm-infra` | `sssm-infra` | Self-managed Cassandra, Qdrant, MLflow, MinIO |
| `monitoring` | `monitoring` | kube-prometheus-stack, Loki, Grafana, Zipkin |
| `ingress` | `kube-system` | AWS Load Balancer Controller, NGINX Ingress Controller |
| `platform` | `kube-system` / `external-secrets` / `argocd` | cert-manager, external-secrets, cluster-autoscaler, Karpenter |

---

## Chart: sssm-services

### Directory layout

```
helm/charts/sssm-services/
├── Chart.yaml
├── values.yaml                        ← base defaults (all environments)
├── templates/
│   └── _helpers.tpl
└── charts/                            ← sub-charts, one per service
    ├── user-service/
    │   ├── Chart.yaml
    │   ├── values.yaml
    │   └── templates/
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       ├── hpa.yaml
    │       ├── pdb.yaml
    │       ├── serviceaccount.yaml
    │       ├── externalsecret.yaml    ← pulls creds from AWS Secrets Manager
    │       └── db-init-job.yaml       ← Flyway migration K8S Job
    ├── post-service/
    │   └── ...                        ← same structure
    ├── comment-service/
    │   └── ...
    ...
    └── post-guard-service/
        ├── Chart.yaml
        ├── values.yaml
        └── templates/
            ├── deployment.yaml
            ├── service.yaml
            ├── hpa.yaml
            ├── pdb.yaml
            ├── serviceaccount.yaml
            ├── externalsecret.yaml
            ├── db-init-job.yaml       ← psql K8S Job
            └── qdrant-init-job.yaml   ← Qdrant collection init K8S Job
```

---

### `Chart.yaml` (parent chart)

```yaml
apiVersion: v2
name: sssm-services
description: SSSM Platform — all application microservices
type: application
version: 1.0.0
appVersion: "1.0.0"

dependencies:
  - name: user-service
    version: "1.0.0"
    repository: "file://charts/user-service"
    condition: userService.enabled

  - name: post-service
    version: "1.0.0"
    repository: "file://charts/post-service"
    condition: postService.enabled

  # ... all 13 services follow the same pattern
```

---

### Base `values.yaml` (parent chart)

```yaml
# Global values shared by all sub-charts
global:
  registry: "123456789.dkr.ecr.us-east-1.amazonaws.com/sssm"
  imagePullPolicy: IfNotPresent
  env: prod
  namespace: sssm

  # AWS Secrets Manager — fetched via External Secrets Operator
  secretsManager:
    region: us-east-1
    refreshInterval: 1h

  # Common resource defaults (overridden per service)
  resources:
    requests:
      cpu:    250m
      memory: 512Mi
    limits:
      cpu:    1000m
      memory: 1Gi

  # Observability
  metrics:
    enabled: true
    path:    /actuator/prometheus
    port:    8080
  tracing:
    zipkinEndpoint: http://zipkin.monitoring:9411/api/v2/spans
    sampleRate:     "0.1"

  # Node selector — run app pods on the 'app' node group
  nodeSelector:
    role: app
  tolerations: []

  # Pod disruption — at least 1 pod always available
  podDisruptionBudget:
    minAvailable: 1

# Per-service enable/disable flags
userService:                  { enabled: true }
mediaService:                 { enabled: true }
postService:                  { enabled: true }
commentService:               { enabled: true }
notificationService:          { enabled: true }
searchService:                { enabled: true }
groupService:                 { enabled: true }
privateMessageService:        { enabled: true }
messageNotificationService:   { enabled: true }
postGuardService:             { enabled: true }
mediaGuardService:            { enabled: true }
userAnalysisService:          { enabled: true }
aiDashboardService:           { enabled: true }
```

---

### Sub-chart: `user-service/values.yaml`

```yaml
image:
  repository: "{{ .Values.global.registry }}/user-service"
  tag:         "1.0.0"      # Updated by Jenkins on every build

replicaCount: 2

service:
  type: ClusterIP
  port: 8081

autoscaling:
  enabled:     true
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 70 }
    - type: Resource
      resource:
        name: memory
        target: { type: Utilization, averageUtilization: 80 }

resources:
  requests: { cpu: 250m, memory: 512Mi }
  limits:   { cpu: 1,    memory: 1Gi   }

# Environment variables — non-secret values
env:
  SPRING_PROFILES_ACTIVE:     prod
  SERVER_PORT:                "8081"
  TRACING_SAMPLE_RATE:        "0.1"

# Secrets pulled from AWS Secrets Manager via External Secrets Operator
externalSecrets:
  - secretName: user-service-secrets
    remoteRef:  sssm/prod/user-service      # AWS Secrets Manager path
    keys:
      - DB_HOST
      - DB_PORT
      - DB_NAME
      - DB_USER
      - DB_PASSWORD
      - REDIS_HOST
      - REDIS_PORT
      - REDIS_PASSWORD
      - KAFKA_BOOTSTRAP_SERVERS
      - KAFKA_SASL_USERNAME
      - KAFKA_SASL_PASSWORD
      - KEYCLOAK_ADMIN_URL
      - KEYCLOAK_ADMIN_CLIENT_SECRET

# DB init job (Flyway)
dbInit:
  enabled: true
  image:   flyway/flyway:10
  secretRef: user-service-secrets

# Probes
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 60
  periodSeconds:       15

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds:       10

# Anti-affinity — spread replicas across nodes
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          topologyKey: kubernetes.io/hostname
          labelSelector:
            matchLabels:
              app: user-service
```

---

### Sub-chart: `post-guard-service/values.yaml` (AI service example)

```yaml
image:
  repository: "{{ .Values.global.registry }}/post-guard-service"
  tag:         "1.0.0"

replicaCount: 2

service:
  type: ClusterIP
  port: 8090

autoscaling:
  enabled:     true
  minReplicas: 2
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 60 }

# AI services need more CPU (inference) and memory (model load)
resources:
  requests: { cpu: 500m,  memory: 2Gi }
  limits:   { cpu: 2,     memory: 4Gi }

# AI services run on app node group by default (CPU inference)
# Set gpu: true to schedule on ai-gpu node group
gpu:
  enabled: false        # set true to enable GPU scheduling

nodeSelector:
  role: app             # overridden to 'ai-gpu' when gpu.enabled=true

tolerations: []

env:
  UVICORN_WORKERS:  "2"
  QDRANT_HOST:      qdrant.sssm-infra     # in-cluster Qdrant service
  QDRANT_GRPC_PORT: "6334"
  MLFLOW_TRACKING_URI: http://mlflow.sssm-infra:5000

externalSecrets:
  - secretName: post-guard-secrets
    remoteRef:  sssm/prod/post-guard-service
    keys:
      - DB_HOST
      - DB_USER
      - DB_PASSWORD
      - REDIS_HOST
      - REDIS_PASSWORD
      - KAFKA_BOOTSTRAP_SERVERS
      - KAFKA_SASL_USERNAME
      - KAFKA_SASL_PASSWORD
      - MINIO_ENDPOINT
      - MINIO_ACCESS_KEY
      - MINIO_SECRET_KEY

dbInit:
  enabled:   true
  image:     postgres:16-alpine
  command:   ["psql", "-f", "/sql/V1__init.sql"]
  secretRef: post-guard-secrets

qdrantInit:
  enabled:  true
  image:    curlimages/curl:8
  script:   |
    curl -X PUT http://qdrant:6333/collections/post_violations \
      -H 'Content-Type: application/json' \
      -d '{"vectors":{"size":384,"distance":"Cosine"},"hnsw_config":{"m":16,"ef_construct":200}}'

livenessProbe:
  httpGet: { path: /api/v1/health, port: 8090 }
  initialDelaySeconds: 90
  periodSeconds:       20

readinessProbe:
  httpGet: { path: /api/v1/health, port: 8090 }
  initialDelaySeconds: 60
  periodSeconds:       10
```

---

### Deployment template (shared pattern)

```yaml
# charts/user-service/templates/deployment.yaml

apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "sssm.fullname" . }}-user-service
  namespace: {{ .Values.global.namespace }}
  labels: {{ include "sssm.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels: {{ include "sssm.selectorLabels" . | nindent 6 }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge:       1
  template:
    metadata:
      labels: {{ include "sssm.selectorLabels" . | nindent 8 }}
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path:   {{ .Values.global.metrics.path }}
        prometheus.io/port:   "{{ .Values.service.port }}"
    spec:
      serviceAccountName: {{ include "sssm.fullname" . }}-user-service
      nodeSelector:        {{ .Values.global.nodeSelector | toYaml | nindent 8 }}
      tolerations:         {{ .Values.global.tolerations  | toYaml | nindent 8 }}
      affinity:            {{ .Values.affinity | toYaml | nindent 8 }}
      terminationGracePeriodSeconds: 60
      containers:
        - name: user-service
          image: {{ .Values.image.repository }}:{{ .Values.image.tag }}
          imagePullPolicy: {{ .Values.global.imagePullPolicy }}
          ports:
            - containerPort: {{ .Values.service.port }}
              protocol:       TCP
          env:
            {{- range $k, $v := .Values.env }}
            - name:  {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
            - name:  ZIPKIN_ENDPOINT
              value: {{ .Values.global.tracing.zipkinEndpoint }}
          envFrom:
            - secretRef:
                name: {{ .Values.externalSecrets[0].secretName }}
          resources: {{ .Values.resources | toYaml | nindent 12 }}
          livenessProbe:  {{ .Values.livenessProbe  | toYaml | nindent 12 }}
          readinessProbe: {{ .Values.readinessProbe | toYaml | nindent 12 }}
          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 5"]   # drain connections before SIGTERM
```

---

### ExternalSecret template

```yaml
# charts/user-service/templates/externalsecret.yaml

apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: {{ .Values.externalSecrets[0].secretName }}
  namespace: {{ .Values.global.namespace }}
spec:
  refreshInterval: {{ .Values.global.secretsManager.refreshInterval }}
  secretStoreRef:
    name:  aws-secrets-manager
    kind:  ClusterSecretStore
  target:
    name:              {{ .Values.externalSecrets[0].secretName }}
    creationPolicy:    Owner
  dataFrom:
    - extract:
        key: {{ .Values.externalSecrets[0].remoteRef }}
```

---

### DB init Job template (Flyway — Spring Boot services)

```yaml
# charts/user-service/templates/db-init-job.yaml

{{- if .Values.dbInit.enabled }}
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ include "sssm.fullname" . }}-db-init-{{ .Release.Revision }}
  namespace: {{ .Values.global.namespace }}
  annotations:
    helm.sh/hook:               pre-install,pre-upgrade
    helm.sh/hook-weight:        "-5"
    helm.sh/hook-delete-policy: hook-succeeded
spec:
  backoffLimit: 3
  template:
    spec:
      restartPolicy: OnFailure
      containers:
        - name: flyway
          image: {{ .Values.dbInit.image }}
          args:
            - -url=jdbc:postgresql://$(DB_HOST):$(DB_PORT)/$(DB_NAME)
            - -user=$(DB_USER)
            - -password=$(DB_PASSWORD)
            - -locations=filesystem:/flyway/sql
            - -validateOnMigrate=true
            - migrate
          envFrom:
            - secretRef:
                name: {{ .Values.externalSecrets[0].secretName }}
          volumeMounts:
            - name: sql-scripts
              mountPath: /flyway/sql
      volumes:
        - name: sql-scripts
          configMap:
            name: {{ include "sssm.fullname" . }}-db-migrations
{{- end }}
```

---

## Chart: sssm-infra

Self-managed components that run **inside** the EKS cluster (not AWS managed services). These are used for workloads where AWS managed equivalents are either not suitable (Cassandra, Qdrant, MLflow) or self-management is preferred.

```yaml
# helm/charts/sssm-infra/Chart.yaml

apiVersion: v2
name: sssm-infra
version: 1.0.0
dependencies:
  # Apache Cassandra — Bitnami chart
  - name:       cassandra
    version:    "11.3.x"
    repository: https://charts.bitnami.com/bitnami
    condition:  cassandra.enabled

  # Qdrant — official chart
  - name:       qdrant
    version:    "1.10.x"
    repository: https://qdrant.github.io/qdrant-helm
    condition:  qdrant.enabled

  # MLflow — community chart (Bitnami)
  - name:       mlflow
    version:    "1.4.x"
    repository: https://charts.bitnami.com/bitnami
    condition:  mlflow.enabled

  # MinIO — official chart
  - name:       minio
    version:    "14.x"
    repository: https://charts.min.io/
    condition:  minio.enabled
```

### Key infra values

```yaml
# helm/charts/sssm-infra/values.yaml

# Cassandra — 3-node cluster on 'infra' node group
cassandra:
  enabled:      true
  replicaCount: 3
  nodeSelector: { role: infra }
  tolerations:
    - key: role, value: infra, effect: NoSchedule
  persistence:
    size: 200Gi
    storageClass: gp3
  resources:
    requests: { cpu: "2",   memory: 8Gi  }
    limits:   { cpu: "4",   memory: 16Gi }

# Qdrant — 3-node cluster (1 per AZ)
qdrant:
  enabled:      true
  replicaCount: 3
  nodeSelector: { role: infra }
  tolerations:
    - key: role, value: infra, effect: NoSchedule
  persistence:
    size: 50Gi
    storageClass: gp3
  resources:
    requests: { cpu: "1",  memory: 2Gi }
    limits:   { cpu: "2",  memory: 4Gi }

# MLflow — 1 server (HA via Aurora PostgreSQL backend + MinIO)
mlflow:
  enabled:            true
  backendStore:
    databaseMigration: false     # migration handled by K8S Job
    postgres:
      enabled:  true
      host:     "$(MLFLOW_DB_HOST)"
      database: mlflow
      user:     "$(MLFLOW_DB_USER)"
      password: "$(MLFLOW_DB_PASSWORD)"
  artifactRoot:
    s3:
      enabled: true
      bucket:  mlflow-artifacts
      awsAccessKeyId:     "$(MINIO_ACCESS_KEY)"
      awsSecretAccessKey: "$(MINIO_SECRET_KEY)"
      s3EndpointUrl:      http://minio:9000
  nodeSelector: { role: infra }

# MinIO — distributed, 4 nodes × 2 drives
minio:
  enabled:    true
  mode:       distributed
  replicas:   4
  drivesPerNode: 2
  nodeSelector: { role: infra }
  persistence:
    size: 100Gi
    storageClass: gp3
  resources:
    requests: { cpu: "500m", memory: 1Gi }
    limits:   { cpu: "2",    memory: 4Gi }
```

---

## Chart: monitoring

Uses `kube-prometheus-stack` with additions.

```yaml
# helm/charts/monitoring/Chart.yaml
dependencies:
  - name: kube-prometheus-stack
    version: "60.x"
    repository: https://prometheus-community.github.io/helm-charts

  - name: loki-stack
    version: "2.10.x"
    repository: https://grafana.github.io/helm-charts

  - name: zipkin
    version: "0.4.x"
    repository: https://openzipkin.github.io/zipkin
```

---

## Environment overrides

```yaml
# helm/environments/prod/values-override.yaml

sssm-services:
  global:
    tracing:
      sampleRate: "0.05"    # 5% in prod (cost)

  user-service:
    replicaCount: 3
    autoscaling:
      minReplicas: 3
      maxReplicas: 20

  post-guard-service:
    replicaCount: 3
    resources:
      requests: { cpu: 1,   memory: 4Gi }
      limits:   { cpu: 4,   memory: 8Gi }

# helm/environments/dev/values-override.yaml
sssm-services:
  global:
    tracing:
      sampleRate: "1.0"    # 100% in dev
  user-service:
    replicaCount: 1
    autoscaling:
      enabled: false
```

---

## Common Helm commands

```bash
# Lint all charts
helm lint helm/charts/sssm-services/ \
  --values helm/environments/prod/values-override.yaml

# Dry-run to preview rendered manifests
helm upgrade --install sssm-services helm/charts/sssm-services/ \
  --namespace sssm \
  --values helm/environments/prod/values-override.yaml \
  --dry-run --debug

# Apply (dev only — prod is ArgoCD-managed)
helm upgrade --install sssm-services helm/charts/sssm-services/ \
  --namespace sssm \
  --values helm/environments/dev/values-override.yaml \
  --wait --timeout 10m

# Rollback
helm rollback sssm-services 2 --namespace sssm

# View history
helm history sssm-services --namespace sssm
```
