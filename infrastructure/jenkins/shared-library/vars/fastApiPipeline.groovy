#!/usr/bin/env groovy
// vars/fastApiPipeline.groovy
// Reusable pipeline for all FastAPI / AI microservices.
// Usage:
//   @Library('sssm-shared-library') _
//   fastApiPipeline(serviceName: 'post-guard-service', servicePort: 8090)

def call(Map config) {
    String serviceName  = config.serviceName
    int    servicePort  = config.servicePort

    String serviceDir    = config.serviceDir    ?: "python-services/${serviceName}"
    boolean sonarEnabled = config.sonarEnabled  != null ? config.sonarEnabled : true
    boolean hasGpu       = config.hasGpu        != null ? config.hasGpu : false

    String ECR_REGISTRY = env.ECR_REGISTRY ?: '123456789.dkr.ecr.us-east-1.amazonaws.com'
    String ECR_REPO     = "${ECR_REGISTRY}/sssm/${serviceName}"
    String IMAGE_TAG    = ''

    pipeline {
        // GPU services need a specific agent; others use standard Python agent
        agent { label hasGpu ? 'gpu-agent' : 'python-agent' }

        options {
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
            timestamps()
        }

        environment {
            AWS_REGION   = 'us-east-1'
            SONAR_TOKEN  = credentials('sonar-token')
            GITHUB_TOKEN = credentials('github-token')
            // Testcontainers needs Docker-in-Docker on agent
            TESTCONTAINERS_RYUK_DISABLED = 'true'
        }

        stages {

            // ── 1. Checkout ───────────────────────────────
            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        String commitShort = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        String branch      = env.BRANCH_NAME ?: 'develop'
                        IMAGE_TAG = (branch == 'main')
                            ? "v${env.BUILD_NUMBER}-${commitShort}"
                            : "${branch}-${commitShort}"
                        env.IMAGE_TAG = IMAGE_TAG
                        env.ECR_REPO  = ECR_REPO
                        echo "Service  : ${serviceName}"
                        echo "Image tag: ${IMAGE_TAG}"
                    }
                }
            }

            // ── 2. Install Dependencies ───────────────────
            stage('Install Dependencies') {
                steps {
                    dir(serviceDir) {
                        sh '''
                            python3 -m venv .venv
                            . .venv/bin/activate
                            pip install --upgrade pip
                            pip install -r requirements.txt
                            pip install -r requirements-dev.txt 2>/dev/null || true
                        '''
                    }
                }
            }

            // ── 3. Lint & Type Check ──────────────────────
            stage('Lint & Type Check') {
                parallel {
                    stage('ruff') {
                        steps {
                            dir(serviceDir) {
                                sh '''
                                    . .venv/bin/activate
                                    ruff check app/ --output-format=github
                                '''
                            }
                        }
                    }
                    stage('mypy') {
                        steps {
                            dir(serviceDir) {
                                sh '''
                                    . .venv/bin/activate
                                    mypy app/ --ignore-missing-imports --no-error-summary || true
                                '''
                            }
                        }
                    }
                }
            }

            // ── 4. Unit Tests ─────────────────────────────
            stage('Unit Tests') {
                steps {
                    dir(serviceDir) {
                        sh '''
                            . .venv/bin/activate
                            pytest tests/unit/ \
                                -v \
                                --cov=app \
                                --cov-report=xml:coverage.xml \
                                --cov-report=term-missing \
                                --cov-fail-under=80 \
                                --junitxml=test-results-unit.xml \
                                -m "not integration and not e2e"
                        '''
                    }
                }
                post {
                    always {
                        junit testResults: "${serviceDir}/test-results-unit.xml",
                              allowEmptyResults: true
                    }
                }
            }

            // ── 5. Integration Tests ──────────────────────
            stage('Integration Tests') {
                when {
                    anyOf { branch 'main'; branch 'develop' }
                }
                steps {
                    dir(serviceDir) {
                        sh '''
                            . .venv/bin/activate
                            pytest tests/integration/ \
                                -v \
                                --junitxml=test-results-integration.xml \
                                -m "integration" \
                                --timeout=120
                        '''
                    }
                }
                post {
                    always {
                        junit testResults: "${serviceDir}/test-results-integration.xml",
                              allowEmptyResults: true
                    }
                }
            }

            // ── 6. SonarQube ──────────────────────────────
            stage('SonarQube') {
                when {
                    allOf {
                        expression { return sonarEnabled }
                        anyOf { branch 'main'; branch 'develop' }
                    }
                }
                steps {
                    withSonarQubeEnv('SonarQube') {
                        dir(serviceDir) {
                            sh """
                                sonar-scanner \
                                    -Dsonar.projectKey=sssm-${serviceName} \
                                    -Dsonar.projectName='SSSM ${serviceName}' \
                                    -Dsonar.sources=app \
                                    -Dsonar.tests=tests \
                                    -Dsonar.python.coverage.reportPaths=coverage.xml \
                                    -Dsonar.python.version=3.12
                            """
                        }
                    }
                    timeout(time: 10, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            // ── 7. Docker Build & Push ────────────────────
            stage('Docker Build & Push') {
                when {
                    anyOf { branch 'main'; branch 'develop' }
                }
                steps {
                    script {
                        dockerBuildPush(
                            serviceName:    serviceName,
                            imageTag:       IMAGE_TAG,
                            ecrRegistry:    ECR_REGISTRY,
                            dockerfilePath: "${serviceDir}/Dockerfile",
                            contextPath:    serviceDir
                        )
                    }
                }
            }

            // ── 8. Helm Lint ──────────────────────────────
            stage('Helm Lint') {
                when {
                    anyOf { branch 'main'; branch 'develop' }
                }
                steps {
                    sh """
                        helm lint infrastructure/helm/charts/sssm-services/charts/${serviceName}/ \
                            --values infrastructure/helm/charts/sssm-services/values.yaml \
                            --set image.tag=${IMAGE_TAG}
                    """
                }
            }

            // ── 9. Update GitOps Repo ─────────────────────
            stage('Update GitOps') {
                when { branch 'main' }
                steps {
                    script {
                        updateGitOps(
                            serviceName: serviceName,
                            imageTag:    IMAGE_TAG,
                            env:         'prod'
                        )
                    }
                }
            }
        }

        post {
            success {
                slackSend(
                    channel: '#ci-cd',
                    color:   'good',
                    message: "✅ *${serviceName}* `${IMAGE_TAG}` built and pushed — <${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"
                )
            }
            failure {
                slackSend(
                    channel: '#ci-cd',
                    color:   'danger',
                    message: "❌ *${serviceName}* build FAILED — <${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"
                )
            }
            always {
                dir(serviceDir) {
                    sh 'rm -rf .venv || true'
                }
                cleanWs()
            }
        }
    }
}
