package io.github.lvoxx.common_core.util;

import java.util.UUID;

import de.huxhorn.sulky.ulid.ULID;

/**
 * Thread-safe ULID generator.
 * Produces time-sortable, UUID-compatible identifiers.
 */
public final class UlidGenerator {

    private static final ULID ULID_INSTANCE = new ULID();

    private UlidGenerator() {
    }

    /**
     * Generate a new ULID string (26 characters, Crockford Base32).
     * Example: {@code "01HXZ8R2J4K5M6N7P8Q9R0S1T2"}
     */
    public static String generate() {
        return ULID_INSTANCE.nextULID();
    }

    /**
     * Generate a new ULID and convert it to a {@link UUID}.
     * Suitable for storage in PostgreSQL / Cassandra UUID columns.
     */
    public static UUID generateAsUUID() {
        ULID.Value value = ULID_INSTANCE.nextValue();
        return new UUID(value.getMostSignificantBits(), value.getLeastSignificantBits());
    }

    /**
     * Parse a ULID string and convert it to a {@link UUID}.
     */
    public static UUID toUUID(String ulid) {
        ULID.Value value = ULID.parseULID(ulid);
        return new UUID(value.getMostSignificantBits(), value.getLeastSignificantBits());
    }
}
