package io.github.lvoxx.common_core.util;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** URL-safe slug utilities. */
public final class SlugUtil {

    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern MULTI_DASH = Pattern.compile("-{2,}");
    private static final Pattern VALID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private SlugUtil() {
    }

    /**
     * Convert arbitrary text to a URL-safe slug.
     * Example: {@code "Hello World!"} → {@code "hello-world"}
     */
    public static String toSlug(String text) {
        if (text == null || text.isBlank())
            return "";
        String normalized = Normalizer.normalize(text.trim().toLowerCase(), Normalizer.Form.NFD);
        String slug = NON_LATIN.matcher(normalized).replaceAll("-");
        slug = MULTI_DASH.matcher(slug).replaceAll("-");
        return slug.replaceAll("^-|-$", "");
    }

    /** Return {@code true} if the value is a valid slug. */
    public static boolean isValidSlug(String slug) {
        return slug != null && VALID_SLUG.matcher(slug).matches();
    }
}