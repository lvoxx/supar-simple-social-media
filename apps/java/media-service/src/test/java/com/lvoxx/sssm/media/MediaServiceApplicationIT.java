package com.lvoxx.sssm.media;

import com.lvoxx.sssm.media.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke integration test: the full application context boots against a real PostgreSQL whose schema
 * was created by the infrastructure-owned migration, proving the JPA mappings validate
 * ({@code ddl-auto=validate}) and the S3/imgproxy beans wire up. Runs in the verify phase (Docker).
 */
class MediaServiceApplicationIT extends PostgresIntegrationTest {

    @Test
    void contextLoads() {
        // Boots the context; ddl-auto=validate fails the test if a mapping drifts from the schema.
    }
}
