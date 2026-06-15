package com.lvoxx.sssm.post.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests (suffix {@code *IT}) that need a real PostgreSQL.
 *
 * <p>It boots a singleton container, creates the {@code sssm} schema, and applies the
 * <em>infrastructure-owned</em> baseline migration ({@code deploy/migrations/post-service}, copied
 * onto the test classpath by the build). The application itself runs unchanged with
 * {@code ddl-auto=validate}, so these tests prove the JPA mappings match the schema that production
 * infrastructure actually creates — the same DB-init rule the rest of the project follows (the app
 * never migrates; infrastructure does).
 *
 * <p>Container start requires Docker, so these tests run in the {@code verify} phase (failsafe), not
 * the Docker-free {@code test} phase.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    /** Pinned to the major version the baseline migration targets (gen_random_uuid() core fn). */
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
        applyInfraMigration();
    }

    /**
     * Applies the real baseline migration into schema {@code sssm}. Run once against the freshly
     * started container; {@code search_path} is set on the connection so the unqualified DDL in the
     * migration lands in {@code sssm}.
     */
    private static void applyInfraMigration() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS sssm");
                st.execute("SET search_path TO sssm");
            }
            ScriptUtils.executeSqlScript(
                    conn, new ClassPathResource("db/migration/V1__baseline.sql"));
            ScriptUtils.executeSqlScript(
                    conn, new ClassPathResource("db/migration/V2__engagement.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply baseline migration to test DB", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresIntegrationTest::jdbcUrlWithSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static String jdbcUrlWithSchema() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=sssm";
    }
}
