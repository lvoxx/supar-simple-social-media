# ArgoCD — GitOps Continuous Delivery

**ArgoCD version:** 2.11  
**Pattern:** App-of-Apps  
**Sync:** Automated with pruning and self-healing (prod), manual gate (prod releases require PR merge)

---

## App-of-Apps structure

```
app-of-apps (root Application)
  │
  ├── platform          ← cert-manager, external-secrets, cluster-autoscaler, karpenter
  ├── ingress           ← AWS Load Balancer Controller, NGINX Ingress
  ├── monitoring        ← Prometheus, Grafana, Loki, Zipkin
  ├── sssm-infra    ← Cassandra, Qdrant, MLflow, MinIO
  └── sssm-services ← all 13 microservices
```

Each child Application watches a path in this Git repository.  
ArgoCD reconciles the live cluster state to match the desired state in Git on every commit.

---

## Bootstrap: app-of-apps.yaml

```yaml
# argocd/bootstrap/app-of-apps.yaml
# Applied once manually after ArgoCD is installed.
# Everything else is self-managed after this.

apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name:      app-of-apps
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default
  source:
    repoURL:        https://github.com/lvoxx/supar-simple-social-media-SpringBoot.git
    targetRevision: HEAD
    path:           infrastructure/argocd/applications

  destination:
    server:    https://kubernetes.default.svc
    namespace: argocd

  syncPolicy:
    automated:
      prune:     true
      selfHeal:  true
    syncOptions:
      - CreateNamespace=true
```

---

## Child Applications

### platform

```yaml
# argocd/applications/platform.yaml

apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name:      platform
  namespace: argocd
spec:
  project: default
  source:
    repoURL:        https://github.com/lvoxx/supar-simple-social-media-SpringBoot.git
    targetRevision: HEAD
    path:           infrastructure/helm/charts/platform
    helm:
      valueFiles:
        - ../../environments/{{ env }}/values-override.yaml

  destination:
    server:    https://kubernetes.default.svc
    namespace: kube-system

  syncPolicy:
    automated:
      prune:    true
      selfHeal: true
    syncOptions:
      - ServerSideApply=true
      - CreateNamespace=true
```

### sssm-services

```yaml
# argocd/applications/sssm-services.yaml

apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name:      sssm-services
  namespace: argocd
  annotations:
    # ArgoCD Image Updater — monitors ECR, updates image tags automatically
    argocd-image-updater.argoproj.io/image-list: >-
      user-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/user-service,
      post-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/post-service,
      comment-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/comment-service,
      notification-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/notification-service,
      search-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/search-service,
      group-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/group-service,
      media-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/media-service,
      private-message-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/private-message-service,
      message-notification-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/message-notification-service,
      post-guard-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/post-guard-service,
      media-guard-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/media-guard-service,
      user-analysis-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/user-analysis-service,
      ai-dashboard-service=123456789.dkr.ecr.us-east-1.amazonaws.com/sssm/ai-dashboard-service
    argocd-image-updater.argoproj.io/write-back-method: git
    argocd-image-updater.argoproj.io/git-branch:        main
    argocd-image-updater.argoproj.io/update-strategy:   semver    # only update on semver tags
spec:
  project: sssm

  source:
    repoURL:        https://github.com/lvoxx/supar-simple-social-media-SpringBoot.git
    targetRevision: HEAD
    path:           infrastructure/helm/charts/sssm-services
    helm:
      valueFiles:
        - ../../environments/prod/values-override.yaml

  destination:
    server:    https://kubernetes.default.svc
    namespace: sssm

  syncPolicy:
    automated:
      prune:    true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - PrunePropagationPolicy=foreground
      - RespectIgnoreDifferences=true
    retry:
      limit: 5
      backoff:
        duration:    5s
        factor:      2
        maxDuration: 3m

  ignoreDifferences:
    - group:        apps
      kind:         Deployment
      jsonPointers:
        - /spec/replicas    # don't revert HPA-managed replica counts
```

### sssm-infra

```yaml
# argocd/applications/sssm-infra.yaml

apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name:      sssm-infra
  namespace: argocd
spec:
  project: sssm

  source:
    repoURL:        https://github.com/lvoxx/supar-simple-social-media-SpringBoot.git
    targetRevision: HEAD
    path:           infrastructure/helm/charts/sssm-infra
    helm:
      valueFiles:
        - ../../environments/prod/values-override.yaml

  destination:
    server:    https://kubernetes.default.svc
    namespace: sssm-infra

  syncPolicy:
    automated:
      prune:    false       # infra: never auto-prune (stateful sets)
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

### monitoring

```yaml
# argocd/applications/monitoring.yaml

apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name:      monitoring
  namespace: argocd
spec:
  project: default
  source:
    repoURL:        https://github.com/lvoxx/supar-simple-social-media-SpringBoot.git
    targetRevision: HEAD
    path:           infrastructure/helm/charts/monitoring
    helm:
      valueFiles:
        - ../../environments/prod/values-override.yaml

  destination:
    server:    https://kubernetes.default.svc
    namespace: monitoring

  syncPolicy:
    automated:
      prune:    true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true
```

---

## ArgoCD Project: sssm

```yaml
# argocd/bootstrap/sssm-project.yaml

apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name:      sssm
  namespace: argocd
spec:
  description: SSSM Platform workloads

  sourceRepos:
    - https://github.com/lvoxx/supar-simple-social-media-SpringBoot.git

  destinations:
    - namespace: sssm
      server:    https://kubernetes.default.svc
    - namespace: sssm-infra
      server:    https://kubernetes.default.svc

  clusterResourceWhitelist:
    - group: ""
      kind:  Namespace

  namespaceResourceWhitelist:
    - group: apps
      kind:  Deployment
    - group: apps
      kind:  StatefulSet
    - group: autoscaling
      kind:  HorizontalPodAutoscaler
    - group: policy
      kind:  PodDisruptionBudget
    - group: batch
      kind:  Job
    - group: ""
      kind:  Service
    - group: ""
      kind:  ConfigMap
    - group: external-secrets.io
      kind:  ExternalSecret

  roles:
    - name: developer
      description: Read-only access for developers
      policies:
        - p, proj:sssm:developer, applications, get, sssm/*, allow
      groups:
        - sssm-developers
    - name: release-manager
      description: Can sync applications
      policies:
        - p, proj:sssm:release-manager, applications, sync, sssm/*, allow
      groups:
        - sssm-platform-team
```

---

## CD pipeline flow

```
Developer merges PR to main
  │
  ├── Jenkins CI triggered:
  │     mvn package / pytest → unit tests
  │     integration tests (Testcontainers)
  │     docker build → docker push to ECR (tag: v1.2.3)
  │
  └── ArgoCD Image Updater detects new ECR image tag
        updates infrastructure/helm/environments/prod/values-override.yaml:
          user-service.image.tag: v1.2.3
        commits to main branch
          │
          └── ArgoCD detects commit to tracked path
                Runs: helm upgrade sssm-services
                Kubernetes: RollingUpdate (maxUnavailable=0, maxSurge=1)
                Helm pre-upgrade hook: db-init Job runs Flyway
                ArgoCD marks sync: Healthy ✓
```

---

## ArgoCD Image Updater configuration

```yaml
# argocd/bootstrap/image-updater-config.yaml
# Configures ECR access via IRSA

apiVersion: v1
kind: ConfigMap
metadata:
  name:      argocd-image-updater-config
  namespace: argocd
data:
  registries.conf: |
    registries:
      - name:        ECR
        api_url:     https://123456789.dkr.ecr.us-east-1.amazonaws.com
        prefix:      123456789.dkr.ecr.us-east-1.amazonaws.com
        credentials: ext:/scripts/ecr-login.sh
        default:     true
        insecure:    false
```

---

## Notifications (ArgoCD → Slack)

```yaml
# argocd/bootstrap/notifications-config.yaml

apiVersion: v1
kind: ConfigMap
metadata:
  name:      argocd-notifications-cm
  namespace: argocd
data:
  service.slack: |
    token: $slack-token

  template.app-deployed: |
    slack:
      attachments: |
        [{
          "color": "#18be52",
          "title": "✅ {{.app.metadata.name}} deployed",
          "text": "Image: {{.app.status.summary.images | join \", \"}}",
          "footer": "ArgoCD"
        }]

  template.app-health-degraded: |
    slack:
      attachments: |
        [{
          "color": "#ff0000",
          "title": "🔴 {{.app.metadata.name}} health degraded",
          "text": "Status: {{.app.status.health.status}}",
          "footer": "ArgoCD"
        }]

  trigger.on-deployed: |
    - when: app.status.operationState.phase in ['Succeeded'] and app.status.health.status == 'Healthy'
      send: [app-deployed]

  trigger.on-health-degraded: |
    - when: app.status.health.status == 'Degraded'
      send: [app-health-degraded]

  subscriptions: |
    - recipients: [slack:deployments]
      triggers: [on-deployed, on-health-degraded]
```
