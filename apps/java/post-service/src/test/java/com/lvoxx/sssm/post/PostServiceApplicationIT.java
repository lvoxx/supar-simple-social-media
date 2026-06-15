package com.lvoxx.sssm.post;

import com.lvoxx.sssm.post.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full application context loads against the real, infrastructure-migrated schema
 * (so the JPA mappings validate). The outbox relay is disabled in tests, so no broker is required.
 */
class PostServiceApplicationIT extends PostgresIntegrationTest {

    @Test
    void contextLoads() {
        // Context startup (with ddl-auto=validate against the baseline migration) is the assertion.
    }
}
