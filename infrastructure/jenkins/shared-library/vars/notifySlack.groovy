#!/usr/bin/env groovy
// vars/notifySlack.groovy
// Centralised Slack notification helper.

def call(Map config) {
    String status      = config.status      ?: 'unknown'   // success | failure | unstable
    String serviceName = config.serviceName ?: 'unknown-service'
    String imageTag    = config.imageTag    ?: ''
    String channel     = config.channel     ?: '#ci-cd'

    Map colorMap = [
        success:  'good',
        failure:  'danger',
        unstable: 'warning',
    ]

    Map emojiMap = [
        success:  '✅',
        failure:  '❌',
        unstable: '⚠️',
    ]

    String color   = colorMap[status] ?: '#888888'
    String emoji   = emojiMap[status] ?: 'ℹ️'
    String tagText = imageTag ? " `${imageTag}`" : ''
    String message = "${emoji} *${serviceName}*${tagText} — ${status.capitalize()} — <${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"

    slackSend(channel: channel, color: color, message: message)
}
