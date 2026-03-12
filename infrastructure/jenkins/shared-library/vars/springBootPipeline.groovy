#!/usr/bin/env groovy
// vars/springBootPipeline.groovy
// Reusable pipeline for all Spring Boot microservices.
// Usage in service Jenkinsfile:
//   @Library('sssm-shared-library') _
//   springBootPipeline(serviceName: 'user-service', servicePort: 8081)

def call(Map config) {
    // ── Required params ───────────────────────────────────
    String serviceName  = config.serviceName   // e.g. 'user-service'
    int    servicePort  = config.servicePort   // e.g. 8081

    // ── Optional params ───────────────────────────────────
    String mavenModule   = config.mavenModule  ?: "spring-services/${serviceName}"
    String javaVersion   = config.javaVersion  ?: '25'
    boolean runIntTests  = config.runIntTests  != null ? config.runIntTests : true
    boolean sonarEnabled = config.sonarEnabled != null ? config.sonarEnabled : true

    // ── Environment ───────────────────────────────────────
    String ECR_REGISTRY  = env.ECR_REGISTRY  ?: '123456789.dkr.ecr.us-east-1.amazonaws.com'
    String ECR_REPO      = "${ECR_REGISTRY}/sssm/${serviceName}"
    String GIT_COMMIT_SHORT = ''
    String IMAGE_TAG        = ''

    pipeline {
        agent { label 'maven-agent' }

        options {
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timeout(time: 45, unit: 'MINUTES')
            disableConcurrentBuilds()
            timestamps()
        }

        environment {
            JAVA_HOME     = "/usr/lib/jvm/java-${javaVersion}-openjdk"
            MAVEN_OPTS    = '-Xmx1024m -XX:MaxMetaspaceSize=512m'
            AWS_REGION    = 'us-east-1'
            SONAR_TOKEN   = credentials('sonar-token')
            GITHUB_TOKEN  = credentials('github-token')
        }

        stages {

            // ── 1. Checkout ───────────────────────────────
            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        String branch = env.BRANCH_NAME ?: 'develop'
                        // Semantic version tag on main, short SHA on other branches
                        IMAGE_TAG = (branch == 'main')
                            ? "v${env.BUILD_NUMBER}-${GIT_COMMIT_SHORT}"
                            : "${branch}-${GIT_COMMIT_SHORT}"
                        env.IMAGE_TAG = IMAGE_TAG
                        env.ECR_REPO  = ECR_REPO
                        echo "Service  : ${serviceName}"
                        echo "Image tag: ${IMAGE_TAG}"
                    }
                }
            }

            // ── 2. Build & Unit Test ──────────────────────
            stage('Build & Unit Test') {
                steps {
                    dir(mavenModule) {
                        sh '''
                            mvn clean package \
                                -DskipTests=false \
                                -Dtest="*Test,*Tests" \
                                -DfailIfNoTests=false \
                                --batch-mode \
                                --no-transfer-progress
                        '''
                    }
                }
                post {
                    always {
                        junit testResults: "${mavenModule}/target/surefire-reports/**/*.xml",
                              allowEmptyResults: true
                        jacoco(
                            execPattern:  "${mavenModule}/target/jacoco.exec",
                            classPattern: "${mavenModule}/target/classes",
                            sourcePattern:"${mavenModule}/src/main/java",
                            minimumLineCoverage: '80'
                        )
                    }
                }
            }

            // ── 3. Integration Test ───────────────────────
            stage('Integration Test') {
                when { expression { return runIntTests } }
                steps {
                    dir(mavenModule) {
                        sh '''
                            mvn failsafe:integration-test \
                                failsafe:verify \
                                -Pintegration-test \
                                --batch-mode \
                                --no-transfer-progress
                        '''
                    }
                }
                post {
                    always {
                        junit testResults: "${mavenModule}/target/failsafe-reports/**/*.xml",
                              allowEmptyResults: true
                    }
                }
            }

            // ── 4. SonarQube Analysis ─────────────────────
            stage('SonarQube') {
                when {
                    allOf {
                        expression { return sonarEnabled }
                        anyOf {
                            branch 'main'
                            branch 'develop'
                        }
                    }
                }
                steps {
                    withSonarQubeEnv('SonarQube') {
                        dir(mavenModule) {
                            sh """
                                mvn sonar:sonar \
                                    -Dsonar.projectKey=sssm-${serviceName} \
                                    -Dsonar.projectName='SSSM ${serviceName}' \
                                    -Dsonar.java.coveragePlugin=jacoco \
                                    -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
                                    --batch-mode \
                                    --no-transfer-progress
                            """
                        }
                    }
                    timeout(time: 10, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            // ── 5. Docker Build & Push ────────────────────
            stage('Docker Build & Push') {
                when {
                    anyOf { branch 'main'; branch 'develop' }
                }
                steps {
                    script {
                        dockerBuildPush(
                            serviceName:   serviceName,
                            imageTag:      IMAGE_TAG,
                            ecrRegistry:   ECR_REGISTRY,
                            dockerfilePath:"${mavenModule}/Dockerfile",
                            contextPath:   mavenModule
                        )
                    }
                }
            }

            // ── 6. Helm Lint ──────────────────────────────
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

            // ── 7. Update GitOps Repo ─────────────────────
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
                echo "✅ Pipeline succeeded for ${serviceName}:${IMAGE_TAG}"
                slackSend(
                    channel: '#ci-cd',
                    color:   'good',
                    message: "✅ *${serviceName}* `${IMAGE_TAG}` built and pushed — <${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"
                )
            }
            failure {
                echo "❌ Pipeline failed for ${serviceName}"
                slackSend(
                    channel: '#ci-cd',
                    color:   'danger',
                    message: "❌ *${serviceName}* build FAILED — <${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"
                )
            }
            always {
                cleanWs()
            }
        }
    }
}
