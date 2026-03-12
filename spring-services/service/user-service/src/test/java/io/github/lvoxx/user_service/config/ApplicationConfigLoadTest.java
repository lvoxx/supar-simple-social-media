package io.github.lvoxx.user_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.lvoxx.redis_starter.service.LockService;
import io.github.lvoxx.user_service.kafka.UserEventPublisher;
import io.github.lvoxx.user_service.repository.AccountHistoryRepository;
import io.github.lvoxx.user_service.repository.BlockRepository;
import io.github.lvoxx.user_service.repository.FollowerRepository;
import io.github.lvoxx.user_service.repository.FollowRequestRepository;
import io.github.lvoxx.user_service.repository.UserRepository;
import io.github.lvoxx.user_service.repository.UserSettingsRepository;
import io.github.lvoxx.user_service.repository.VerificationRepository;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;

/**
 * Smoke test to verify the Spring application context loads correctly
 * with all infrastructure dependencies mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationConfigLoadTest {

    // Mock all external infrastructure to allow context to load without real
    // services
    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private FollowerRepository followerRepository;

    @MockitoBean
    private FollowRequestRepository followRequestRepository;

    @MockitoBean
    private BlockRepository blockRepository;

    @MockitoBean
    private UserSettingsRepository userSettingsRepository;

    @MockitoBean
    private AccountHistoryRepository accountHistoryRepository;

    @MockitoBean
    private VerificationRepository verificationRepository;

    @MockitoBean
    private UserEventPublisher userEventPublisher;

    @MockitoBean
    private LockService lockService;

    @MockitoBean
    private ReactiveKafkaProducerTemplate<?, ?> reactiveKafkaProducerTemplate;

    @Test
    void contextLoads() {
        // Verifies that the Spring context starts up without errors
    }
}
