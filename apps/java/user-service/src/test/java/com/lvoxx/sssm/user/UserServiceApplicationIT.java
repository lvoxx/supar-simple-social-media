package com.lvoxx.sssm.user_service;

import com.lvoxx.sssm.user_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke integration test: the full application context starts against a real PostgreSQL with the
 * infrastructure baseline schema. Because the app runs {@code ddl-auto=validate}, a green context
 * load also proves every JPA mapping matches the infra-owned schema.
 */
class UserServiceApplicationIT extends PostgresIntegrationTest {

    @Test
    void contextLoads() {
        // Context bootstrap (incl. Hibernate schema validation) is the assertion.
    }
}
