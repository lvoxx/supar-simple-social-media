package com.lvoxx.sssm.media.service;

import com.lvoxx.sssm.media.config.ImgproxyProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Builds signed imgproxy URLs for an image's AVIF/WebP variants. media-service never transcodes:
 * each URL tells imgproxy to fetch the R2 original ({@code sourceUrlPrefix + objectKey}) and resize
 * it to a target width in a target format on demand.
 *
 * <p>URL shape follows imgproxy's signed form
 * {@code <base>/<signature>/<processing>/<base64url(source)>.<ext>}, where the signature is
 * {@code base64url(HMAC-SHA256(saltBytes || pathBytes))} over the path (everything after the
 * signature segment). With no key/salt configured the {@code insecure} placeholder is used so local
 * dev works against an imgproxy running with signing disabled.
 */
@Component
public class ImgproxyUrlBuilder {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ImgproxyProperties props;

    public ImgproxyUrlBuilder(ImgproxyProperties props) {
        this.props = props;
    }

    /** One signed URL per configured width × format for the given R2 object. */
    public List<Variant> variantsFor(String objectKey) {
        String source = props.sourceUrlPrefix() + objectKey;
        String encodedSource = URL_ENCODER.encodeToString(source.getBytes(StandardCharsets.UTF_8));

        List<Variant> variants = new ArrayList<>(props.widths().size() * props.formats().size());
        for (Integer width : props.widths()) {
            for (String format : props.formats()) {
                // rs:fit:<width>:0 — resize to fit the width, height auto (0) to keep aspect ratio.
                String path = "/rs:fit:" + width + ":0/" + encodedSource + "." + format;
                String url = props.baseUrl() + "/" + sign(path) + path;
                variants.add(new Variant(width, format, url));
            }
        }
        return variants;
    }

    private String sign(String path) {
        if (props.key() == null || props.key().isBlank()
                || props.salt() == null || props.salt().isBlank()) {
            return "insecure";
        }
        try {
            byte[] keyBytes = HexFormat.of().parseHex(props.key());
            byte[] saltBytes = HexFormat.of().parseHex(props.salt());
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGO));
            mac.update(saltBytes);
            byte[] signature = mac.doFinal(path.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign imgproxy URL", e);
        }
    }

    /** A single served representation of an image: a width, a format, and the signed URL. */
    public record Variant(int width, String format, String url) {
    }
}
