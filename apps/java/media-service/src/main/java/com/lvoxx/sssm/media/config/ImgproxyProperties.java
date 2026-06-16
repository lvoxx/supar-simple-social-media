package com.lvoxx.sssm.media.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * imgproxy settings, bound from {@code sssm.imgproxy.*}. media-service does not transcode images
 * itself: it hands clients signed imgproxy URLs that imgproxy resolves on demand against the R2
 * original. For every configured width × format pair a variant URL is produced.
 *
 * <p>{@code key}/{@code salt} are hex-encoded HMAC material matching imgproxy's
 * {@code IMGPROXY_KEY}/{@code IMGPROXY_SALT}. When either is blank, unsigned ({@code /insecure/…})
 * URLs are emitted — convenient for local dev where imgproxy runs with signing disabled.
 *
 * @param baseUrl         public base URL of the imgproxy deployment
 * @param key             hex-encoded HMAC key (blank ⇒ unsigned URLs)
 * @param salt            hex-encoded HMAC salt (blank ⇒ unsigned URLs)
 * @param sourceUrlPrefix prepended to an object key to form imgproxy's source URL (e.g.
 *                        {@code s3://sssm-media/}), so imgproxy fetches the original from R2
 * @param widths          target widths (px) to offer; height auto-scales to preserve aspect ratio
 * @param formats         output formats to offer per width (e.g. {@code avif}, {@code webp})
 */
@ConfigurationProperties(prefix = "sssm.imgproxy")
public record ImgproxyProperties(
        String baseUrl,
        String key,
        String salt,
        String sourceUrlPrefix,
        List<Integer> widths,
        List<String> formats) {

    public ImgproxyProperties {
        if (sourceUrlPrefix == null) {
            sourceUrlPrefix = "";
        }
        widths = (widths == null || widths.isEmpty()) ? List.of(320, 640, 1280) : List.copyOf(widths);
        formats = (formats == null || formats.isEmpty()) ? List.of("avif", "webp") : List.copyOf(formats);
    }
}
