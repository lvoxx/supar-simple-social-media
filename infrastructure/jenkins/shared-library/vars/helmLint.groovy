#!/usr/bin/env groovy
// vars/helmLint.groovy
// Run helm lint on a service sub-chart. Also renders the chart
// with --dry-run to catch template errors before pushing.

def call(Map config) {
    String serviceName = config.serviceName
    String imageTag    = config.imageTag    ?: 'latest'
    String targetEnv   = config.env         ?: 'prod'

    String chartPath  = "infrastructure/helm/charts/sssm-services/charts/${serviceName}"
    String parentVals = "infrastructure/helm/charts/sssm-services/values.yaml"
    String envVals    = "infrastructure/helm/environments/${targetEnv}/values-override.yaml"

    sh """
        echo "▶ Linting chart: ${serviceName}"
        helm lint ${chartPath} \
            --values ${parentVals} \
            --set image.tag=${imageTag} \
            --strict

        echo "▶ Dry-run template render for ${serviceName}"
        helm template ${serviceName} ${chartPath} \
            --values ${parentVals} \
            --set image.tag=${imageTag} \
            --debug > /dev/null
    """
}
