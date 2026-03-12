package io.github.lvoxx.jenkins

// src/io/github/lvoxx/jenkins/GitUtils.groovy
// Utility methods for Git operations.

class GitUtils implements Serializable {

    static String shortCommit(def script) {
        return script.sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
    }

    static String currentBranch(def script) {
        // Works in both multibranch and regular pipelines
        String branch = script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: 'unknown'
        return branch.replaceAll('^origin/', '')
    }

    static String buildConventionalCommitMessage(String type, String scope, String message) {
        // e.g. "chore(gitops): update user-service to v42-abc1234 [skip ci]"
        return "${type}(${scope}): ${message}"
    }

    static String latestTag(def script) {
        return script.sh(
            script: 'git describe --tags --abbrev=0 2>/dev/null || echo "none"',
            returnStdout: true
        ).trim()
    }
}
