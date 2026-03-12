#!/usr/bin/env groovy
// vars/dockerBuildPush.groovy
// ECR login → docker build → docker push (current + latest tag).
// Called from springBootPipeline and fastApiPipeline.

def call(Map config) {
    String serviceName    = config.serviceName
    String imageTag       = config.imageTag
    String ecrRegistry    = config.ecrRegistry    ?: env.ECR_REGISTRY
    String dockerfilePath = config.dockerfilePath ?: 'Dockerfile'
    String contextPath    = config.contextPath    ?: '.'
    String awsRegion      = config.awsRegion      ?: 'us-east-1'

    String fullImage = "${ecrRegistry}/sssm/${serviceName}"

    // ── ECR login ─────────────────────────────────────────
    sh """
        aws ecr get-login-password \
            --region ${awsRegion} \
        | docker login \
            --username AWS \
            --password-stdin \
            ${ecrRegistry}
    """

    // ── Build ─────────────────────────────────────────────
    sh """
        docker build \
            --file ${dockerfilePath} \
            --tag  ${fullImage}:${imageTag} \
            --tag  ${fullImage}:latest \
            --build-arg BUILD_DATE=\$(date -u +'%Y-%m-%dT%H:%M:%SZ') \
            --build-arg VCS_REF=\$(git rev-parse HEAD) \
            --build-arg SERVICE_NAME=${serviceName} \
            --build-arg IMAGE_TAG=${imageTag} \
            --cache-from ${fullImage}:latest \
            --label "org.opencontainers.image.created=\$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
            --label "org.opencontainers.image.revision=\$(git rev-parse HEAD)" \
            --label "org.opencontainers.image.source=\$(git remote get-url origin)" \
            --label "sssm.service=${serviceName}" \
            ${contextPath}
    """

    // ── Scan (ECR image scanning) ─────────────────────────
    // Push triggers the scan; we do not block CI on it by default.
    // Add --wait-for-scan-findings to enforce blocking security gate.

    // ── Push ──────────────────────────────────────────────
    sh """
        docker push ${fullImage}:${imageTag}
        docker push ${fullImage}:latest
    """

    // ── Clean up local images to save disk ────────────────
    sh """
        docker rmi ${fullImage}:${imageTag} || true
        docker rmi ${fullImage}:latest      || true
    """

    echo "Pushed: ${fullImage}:${imageTag}"
}
