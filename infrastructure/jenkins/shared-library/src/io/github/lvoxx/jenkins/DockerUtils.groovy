package io.github.lvoxx.jenkins

// src/io/github/lvoxx/jenkins/DockerUtils.groovy
// Utility methods for Docker/ECR operations used by pipeline vars.

class DockerUtils implements Serializable {

    static String buildImageName(String registry, String project, String service) {
        return "${registry}/${project}/${service}"
    }

    static String buildTag(String branch, int buildNumber, String commitShort) {
        if (branch == 'main') {
            return "v${buildNumber}-${commitShort}"
        }
        String safeBranch = branch.replaceAll('[^a-zA-Z0-9._-]', '-')
        return "${safeBranch}-${commitShort}"
    }

    static boolean isReleaseBranch(String branch) {
        return branch == 'main'
    }

    static boolean isDeployableBranch(String branch) {
        return branch == 'main' || branch == 'develop'
    }
}
