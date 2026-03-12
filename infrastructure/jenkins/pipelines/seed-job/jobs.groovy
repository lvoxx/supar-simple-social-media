// pipelines/seed-job/jobs.groovy
// Job DSL script — defines all multibranch pipeline jobs for SSSM.
// Executed by the seed Jenkinsfile above.

// ── Constants ─────────────────────────────────────────────────────────────────
String GIT_REPO     = 'https://github.com/lvoxx/supar-simple-social-media-SpringBoot.git'
String CREDENTIALS  = 'github-credentials'
String SHARED_LIB   = 'sssm-shared-library'

// Folder structure
folder('SSSM')                { displayName('SSSM Social Platform') }
folder('SSSM/Spring-Services'){ displayName('Spring Boot Services') }
folder('SSSM/AI-Services')    { displayName('AI / FastAPI Services') }
folder('SSSM/Platform')       { displayName('Platform & Infrastructure') }

// ── Spring Boot Services ──────────────────────────────────────────────────────
def springServices = [
    [name: 'user-service',                 port: 8081],
    [name: 'media-service',                port: 8082],
    [name: 'post-service',                 port: 8083],
    [name: 'comment-service',              port: 8084],
    [name: 'notification-service',         port: 8085],
    [name: 'search-service',               port: 8086],
    [name: 'group-service',                port: 8087],
    [name: 'private-message-service',      port: 8088],
    [name: 'message-notification-service', port: 8089],
]

springServices.each { svc ->
    multibranchPipelineJob("SSSM/Spring-Services/${svc.name}") {
        displayName(svc.name)
        description("CI pipeline for ${svc.name} (Spring Boot, port ${svc.port})")

        branchSources {
            github {
                id(svc.name)
                repoOwner('your-org')
                repository('sssm-platform')
                credentialsId(CREDENTIALS)
                buildForkPRHead(false)
                buildForkPRMerge(false)
                buildOriginBranch(true)
                buildOriginBranchWithPR(false)
                buildOriginPRHead(false)
                buildOriginPRMerge(true)
            }
        }

        factory {
            workflowBranchProjectFactory {
                scriptPath("infrastructure/jenkins/pipelines/spring-services/${svc.name}/Jenkinsfile")
            }
        }

        orphanedItemStrategy {
            discardOldItems {
                numToKeep(5)
            }
        }

        triggers {
            // Rebuild every 4h in case webhook was missed
            periodic(240)
        }

        configure { node ->
            def traits = node / 'sources' / 'data' / 'jenkins.branch.BranchSource' / 'source' / 'traits'
            traits << 'org.jenkinsci.plugins.github__branch__source.BranchDiscoveryTrait' {
                strategyId(1)  // Exclude branches that are also filed as PRs
            }
            traits << 'org.jenkinsci.plugins.github__branch__source.OriginPullRequestDiscoveryTrait' {
                strategyId(1)  // Merge with target branch
            }
            // Only build main, develop, feature/*, bugfix/*, hotfix/*
            traits << 'jenkins.scm.impl.trait.RegexSCMHeadFilterTrait' {
                regex('(main|develop|feature/.*|bugfix/.*|hotfix/.*)')
            }
        }
    }
}

// ── AI / FastAPI Services ─────────────────────────────────────────────────────
def aiServices = [
    [name: 'post-guard-service',    port: 8090],
    [name: 'media-guard-service',   port: 8091],
    [name: 'user-analysis-service', port: 8092],
    [name: 'ai-dashboard-service',  port: 8093],
]

aiServices.each { svc ->
    multibranchPipelineJob("SSSM/AI-Services/${svc.name}") {
        displayName(svc.name)
        description("CI pipeline for ${svc.name} (FastAPI, port ${svc.port})")

        branchSources {
            github {
                id(svc.name)
                repoOwner('your-org')
                repository('sssm-platform')
                credentialsId(CREDENTIALS)
                buildOriginBranch(true)
                buildOriginPRMerge(true)
            }
        }

        factory {
            workflowBranchProjectFactory {
                scriptPath("infrastructure/jenkins/pipelines/ai-services/${svc.name}/Jenkinsfile")
            }
        }

        orphanedItemStrategy {
            discardOldItems { numToKeep(5) }
        }

        configure { node ->
            def traits = node / 'sources' / 'data' / 'jenkins.branch.BranchSource' / 'source' / 'traits'
            traits << 'jenkins.scm.impl.trait.RegexSCMHeadFilterTrait' {
                regex('(main|develop|feature/.*|bugfix/.*)')
            }
        }
    }
}

// ── Platform Jobs ─────────────────────────────────────────────────────────────

// Helm lint all charts together (run on PR to infrastructure/)
pipelineJob('SSSM/Platform/helm-lint-all') {
    displayName('Helm Lint — All Charts')
    description('Lint all Helm charts in a single job. Triggered on infrastructure/ changes.')

    definition {
        cpsScm {
            scm {
                git {
                    remote { url(GIT_REPO); credentials(CREDENTIALS) }
                    branch('*/main')
                }
            }
            scriptPath('infrastructure/jenkins/pipelines/platform/helm-lint-all/Jenkinsfile')
        }
    }

    triggers {
        scm('H/15 * * * *')
    }

    logRotator { numToKeep(20) }
}

// Terraform plan (runs on PR to infrastructure/terraform/)
pipelineJob('SSSM/Platform/terraform-plan') {
    displayName('Terraform Plan — Prod')
    description('Run terraform plan on prod environment. Triggered on terraform/ changes.')

    definition {
        cpsScm {
            scm {
                git {
                    remote { url(GIT_REPO); credentials(CREDENTIALS) }
                    branch('*/main')
                }
            }
            scriptPath('infrastructure/jenkins/pipelines/platform/terraform-plan/Jenkinsfile')
        }
    }

    logRotator { numToKeep(10) }
}

// Nightly full integration test run across all services
pipelineJob('SSSM/Platform/nightly-integration-tests') {
    displayName('Nightly — Full Integration Tests')
    description('Runs integration tests for all services nightly against the dev cluster.')

    definition {
        cpsScm {
            scm {
                git {
                    remote { url(GIT_REPO); credentials(CREDENTIALS) }
                    branch('*/develop')
                }
            }
            scriptPath('infrastructure/jenkins/pipelines/platform/nightly-tests/Jenkinsfile')
        }
    }

    triggers {
        cron('H 2 * * *')  // 02:xx every night
    }

    logRotator { numToKeep(14) }
}

// Secret rotation job
pipelineJob('SSSM/Platform/rotate-secrets') {
    displayName('Rotate AWS Secrets')
    description('Manually triggered job to rotate a named secret and restart the affected service.')

    parameters {
        stringParam('SECRET_NAME', '', 'AWS Secrets Manager path, e.g. sssm/prod/user-service')
        choiceParam('ENVIRONMENT', ['prod', 'staging', 'dev'], 'Target environment')
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote { url(GIT_REPO); credentials(CREDENTIALS) }
                    branch('*/main')
                }
            }
            scriptPath('infrastructure/jenkins/pipelines/platform/rotate-secrets/Jenkinsfile')
        }
    }

    logRotator { numToKeep(20) }
}
