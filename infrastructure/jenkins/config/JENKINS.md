# Jenkins Configuration Reference
# infrastructure/jenkins/config/JENKINS.md
#
# Documents required plugins, credentials, agent labels, and
# the shared library registration.  Apply these settings via
# Jenkins Configuration-as-Code (JCasC) or manually in the UI.

---

## Required Plugins

| Plugin | Purpose |
|--------|---------|
| Pipeline | Core pipeline support |
| Multibranch Pipeline | Per-branch CI |
| Job DSL | Seed job / jobs.groovy |
| GitHub Branch Source | GitHub integration |
| Credentials Binding | Injecting secrets |
| AWS Steps (`pipeline-aws`) | ECR login, `withAWS()` |
| SonarQube Scanner | `withSonarQubeEnv()`, `waitForQualityGate` |
| JaCoCo | Coverage threshold enforcement |
| Slack Notification | `slackSend()` |
| Blue Ocean (optional) | Modern UI |
| Kubernetes | Dynamic pod agents |
| Docker Pipeline | `docker.build()` utilities |
| Parameterized Trigger | Cross-job triggering |
| Git | SCM checkout |
| Timestamper | Timestamps in logs |

---

## Credentials (stored in Jenkins Credentials Store)

| ID | Type | Description |
|----|------|-------------|
| `github-credentials` | Username/Password | GitHub token (read + write for GitOps push) |
| `github-token` | Secret text | GitHub personal access token (PR comments) |
| `sonar-token` | Secret text | SonarQube analysis token |
| `aws-terraform-credentials` | AWS Credentials | IAM user for Terraform (AdministratorAccess scoped) |
| `aws-prod-credentials` | AWS Credentials | IAM user for prod deployments |
| `aws-staging-credentials` | AWS Credentials | IAM user for staging |
| `aws-dev-credentials` | AWS Credentials | IAM user for dev |
| `kubeconfig-prod` | Secret file | kubeconfig for prod EKS cluster |
| `kubeconfig-staging` | Secret file | kubeconfig for staging EKS cluster |
| `kubeconfig-dev` | Secret file | kubeconfig for dev EKS cluster |
| `slack-token` | Secret text | Slack bot token for notifications |

---

## Shared Library Registration

In **Manage Jenkins → Configure System → Global Pipeline Libraries**:

| Field | Value |
|-------|-------|
| Name | `sssm-shared-library` |
| Default version | `main` |
| Allow default version override | ✅ |
| Load implicitly | ❌ |
| Repository URL | `https://github.com/lvoxx/supar-simple-social-media-SpringBoot.git` |
| Credentials | `github-credentials` |
| Library path | `infrastructure/jenkins/shared-library` |

---

## Agent Labels

Jenkins agents must be tagged with these labels.
Agents are provisioned as Kubernetes pods using the Kubernetes plugin.

| Label | Runtime | Tools installed |
|-------|---------|-----------------|
| `maven-agent` | `eclipse-temurin:25` | Maven 3.9, Docker CLI, Helm, kubectl, yq, awscli |
| `python-agent` | `python:3.12` | pip, pytest, ruff, mypy, sonar-scanner, Docker CLI, Helm, kubectl, yq, awscli |
| `helm-agent` | `alpine/helm:3.15` | Helm, kubectl |
| `terraform-agent` | `hashicorp/terraform:1.7` | Terraform, awscli |
| `kubectl-agent` | `bitnami/kubectl:1.30` | kubectl, awscli |
| `gpu-agent` | `nvidia/cuda:12.4-runtime` | Python 3.12, pip, Docker CLI |
| `master` | Jenkins built-in node | Job DSL seed only |

### Example Kubernetes pod template (maven-agent)

```yaml
# Add in Jenkins → Manage Nodes → Configure Clouds → Kubernetes
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-agent
  containers:
    - name: maven
      image: eclipse-temurin:25
      command: [cat]
      tty: true
      resources:
        requests: { cpu: 1, memory: 2Gi }
        limits:   { cpu: 2, memory: 4Gi }
      volumeMounts:
        - name: docker-sock
          mountPath: /var/run/docker.sock
        - name: maven-cache
          mountPath: /root/.m2
  volumes:
    - name: docker-sock
      hostPath: { path: /var/run/docker.sock }
    - name: maven-cache
      persistentVolumeClaim: { claimName: maven-cache-pvc }
  nodeSelector:
    role: system
```

---

## SonarQube Server Registration

In **Manage Jenkins → Configure System → SonarQube servers**:

| Field | Value |
|-------|-------|
| Name | `SonarQube` |
| Server URL | `http://sonarqube.monitoring.svc.cluster.local:9000` |
| Authentication token | (use Jenkins credential `sonar-token`) |

---

## ECR_REGISTRY Global Environment Variable

In **Manage Jenkins → Configure System → Global properties → Environment variables**:

| Name | Value |
|------|-------|
| `ECR_REGISTRY` | `123456789.dkr.ecr.us-east-1.amazonaws.com` |

---

## Webhook Setup (GitHub → Jenkins)

In the GitHub repository settings → Webhooks, add:

- **Payload URL**: `https://jenkins.sssm.com/github-webhook/`
- **Content type**: `application/json`
- **Events**: `Push`, `Pull request`

This triggers multibranch pipelines immediately on push/PR rather than
waiting for the 4-hour polling fallback defined in `jobs.groovy`.
