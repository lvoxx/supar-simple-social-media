package io.github.lvoxx.user_service.testcontainers;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.lvoxx.user_service.config.NoCacheLoadConfig;

@SuppressWarnings("resource")
@Testcontainers
@DataR2dbcTest
@Import(NoCacheLoadConfig.class)
public abstract class AbstractDatabaseTestContainer {

    @Container
    static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer(TestContainerImages.POSTGRES)
                .withDatabaseName("test")
                .withUsername("root")
                .withPassword("Te3tP4ssW@r$")
                .withInitScript("schemas.sql");
    }

    @BeforeAll
    static void beforeAll() {
        POSTGRES.start();
        POSTGRES.waitingFor(new HostPortWaitStrategy());
    }

    @AfterAll
    static void afterAll() {
        POSTGRES.stop();
        POSTGRES.close();
    }

    @DynamicPropertySource
    static void configureR2dbc(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
                () -> String.format("r2dbc:postgresql://%s:%d/%s",
                        POSTGRES.getHost(),
                        POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                        POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }
}