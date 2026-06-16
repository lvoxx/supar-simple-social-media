package com.lvoxx.sssm.media.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cloudflare R2 (S3-compatible) connection settings, bound from {@code sssm.storage.*}. R2 requires
 * path-style addressing and an explicit endpoint; {@code region} is nominal ("auto") since R2 has a
 * single global endpoint. Credentials are R2 API tokens.
 *
 * @param endpoint   R2 S3 API endpoint, e.g. {@code https://<account>.r2.cloudflarestorage.com}
 * @param region     nominal region for SigV4 signing (R2 accepts {@code auto})
 * @param bucket     R2 bucket holding the original objects
 * @param accessKey  R2 access key id
 * @param secretKey  R2 secret access key
 * @param presignTtl how long an issued presigned upload URL stays valid
 */
@ConfigurationProperties(prefix = "sssm.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        Duration presignTtl) {

    public StorageProperties {
        if (region == null || region.isBlank()) {
            region = "auto";
        }
        if (presignTtl == null) {
            presignTtl = Duration.ofMinutes(15);
        }
    }
}
