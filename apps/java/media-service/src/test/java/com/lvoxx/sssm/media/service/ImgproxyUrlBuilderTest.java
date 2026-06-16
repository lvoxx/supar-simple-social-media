package com.lvoxx.sssm.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lvoxx.sssm.media.config.ImgproxyProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the imgproxy URL builder: it must produce one URL per width × format, target the
 * configured base URL, carry the right format extension, and switch between signed and unsigned
 * ({@code /insecure/}) forms depending on whether HMAC key/salt are configured.
 */
class ImgproxyUrlBuilderTest {

    private static final String OBJECT_KEY = "media/owner/abc.jpg";

    @Test
    void buildsOneVariantPerWidthTimesFormat() {
        ImgproxyUrlBuilder builder = new ImgproxyUrlBuilder(props("", ""));

        List<ImgproxyUrlBuilder.Variant> variants = builder.variantsFor(OBJECT_KEY);

        // 2 widths × 2 formats = 4 variants.
        assertThat(variants).hasSize(4);
        assertThat(variants).extracting(ImgproxyUrlBuilder.Variant::format)
                .containsOnly("avif", "webp");
        assertThat(variants).allSatisfy(v -> {
            assertThat(v.url()).startsWith("http://imgproxy.test/");
            assertThat(v.url()).endsWith("." + v.format());
            assertThat(v.url()).contains("/rs:fit:" + v.width() + ":0/");
        });
    }

    @Test
    void usesInsecurePlaceholderWhenNoKeyOrSalt() {
        ImgproxyUrlBuilder builder = new ImgproxyUrlBuilder(props("", ""));

        ImgproxyUrlBuilder.Variant variant = builder.variantsFor(OBJECT_KEY).get(0);

        assertThat(variant.url()).startsWith("http://imgproxy.test/insecure/");
    }

    @Test
    void signsWithHmacWhenKeyAndSaltConfigured() {
        // Arbitrary but valid hex key/salt.
        ImgproxyUrlBuilder builder = new ImgproxyUrlBuilder(props("a1b2c3d4", "deadbeef"));

        ImgproxyUrlBuilder.Variant variant = builder.variantsFor(OBJECT_KEY).get(0);

        // A real signature replaces the "insecure" placeholder and is deterministic per input.
        assertThat(variant.url()).doesNotContain("/insecure/");
        assertThat(builder.variantsFor(OBJECT_KEY).get(0).url()).isEqualTo(variant.url());
    }

    private static ImgproxyProperties props(String key, String salt) {
        return new ImgproxyProperties(
                "http://imgproxy.test", key, salt, "s3://sssm-media-test/",
                List.of(320, 640), List.of("avif", "webp"));
    }
}
