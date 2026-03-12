#!/usr/bin/env groovy
// vars/updateGitOps.groovy
// Commits updated image.tag into the GitOps repo so ArgoCD Image Updater
// (or a direct value update) triggers a Helm upgrade.

def call(Map config) {
    String serviceName = config.serviceName
    String imageTag    = config.imageTag
    String targetEnv   = config.env ?: 'prod'
    String gitopsRepo  = config.gitopsRepo ?: 'https://github.com/lvoxx/supar-simple-social-media-SpringBoot.git'
    String gitBranch   = config.gitBranch  ?: 'main'

    // Camel-case the service name for the YAML key  (user-service → userService)
    String serviceKey = serviceName
        .replaceAll(/-([a-z])/) { _, c -> c.toUpperCase() }

    String valuesFile = "infrastructure/helm/environments/${targetEnv}/values-override.yaml"

    withCredentials([
        usernamePassword(
            credentialsId: 'github-credentials',
            usernameVariable: 'GIT_USER',
            passwordVariable: 'GIT_TOKEN'
        )
    ]) {
        sh """
            git config user.email "jenkins@sssm.com"
            git config user.name  "Jenkins CI"

            # Pull latest to avoid conflicts
            git fetch origin ${gitBranch}
            git checkout ${gitBranch}
            git pull origin ${gitBranch}

            # Update image.tag using yq (available on Jenkins agents)
            yq e '.["sssm-services"]["${serviceKey}"].image.tag = "${imageTag}"' \
                -i ${valuesFile}

            # Commit only if there is a change
            if git diff --quiet ${valuesFile}; then
                echo "No change in values-override.yaml, skipping commit."
            else
                git add ${valuesFile}
                git commit -m "chore(gitops): update ${serviceName} to ${imageTag} [skip ci]"
                git push https://\${GIT_USER}:\${GIT_TOKEN}@github.com/your-org/sssm-platform.git ${gitBranch}
                echo "GitOps updated: ${serviceName} → ${imageTag}"
            fi
        """
    }
}
