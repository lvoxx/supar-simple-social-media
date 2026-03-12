package io.github.lvoxx.user_service.kafka;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
import org.springframework.kafka.support.SendResult;

import io.github.lvoxx.user_service.entity.User;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.SenderResult;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class UserEventPublisherTest {

    @Mock
    private ReactiveKafkaProducerTemplate<String, SpecificRecord> kafka;

    private UserEventPublisher userEventPublisher;

    private UUID userId;
    private User testUser;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        userEventPublisher = new UserEventPublisher(kafka);

        userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .username("alice")
                .displayName("Alice")
                .avatarUrl("http://example.com/alice.jpg")
                .backgroundUrl("http://example.com/alice-bg.jpg")
                .build();

        // Default mock: return a successful SenderResult for all sends
        SenderResult<Object> senderResult = mock(SenderResult.class);
        when(kafka.send(anyString(), anyString(), any(SpecificRecord.class)))
                .thenReturn(Mono.just(senderResult));
    }

    // ── publishProfileUpdated ─────────────────────────────────────────────────

    @Test
    void publishProfileUpdated_givenUser_sendsToCorrectTopic() {
        StepVerifier.create(userEventPublisher.publishProfileUpdated(testUser))
                .verifyComplete();

        verify(kafka).send(eq("user.profile.updated"), eq(userId.toString()), any(SpecificRecord.class));
    }

    // ── publishAvatarChanged ──────────────────────────────────────────────────

    @Test
    void publishAvatarChanged_givenUser_sendsToAvatarChangedTopic() {
        StepVerifier.create(userEventPublisher.publishAvatarChanged(testUser))
                .verifyComplete();

        verify(kafka).send(eq("user.avatar.changed"), eq(userId.toString()), any(SpecificRecord.class));
    }

    @Test
    void publishAvatarChanged_givenNullAvatarUrl_defaultsToEmptyString() {
        User userWithNullAvatar = User.builder()
                .id(userId)
                .username("alice")
                .avatarUrl(null)
                .build();

        StepVerifier.create(userEventPublisher.publishAvatarChanged(userWithNullAvatar))
                .verifyComplete();

        verify(kafka).send(eq("user.avatar.changed"), eq(userId.toString()), any(SpecificRecord.class));
    }

    // ── publishBackgroundChanged ──────────────────────────────────────────────

    @Test
    void publishBackgroundChanged_givenUser_sendsToBackgroundChangedTopic() {
        StepVerifier.create(userEventPublisher.publishBackgroundChanged(testUser))
                .verifyComplete();

        verify(kafka).send(eq("user.background.changed"), eq(userId.toString()), any(SpecificRecord.class));
    }

    @Test
    void publishBackgroundChanged_givenNullBackgroundUrl_defaultsToEmptyString() {
        User userWithNullBg = User.builder()
                .id(userId)
                .username("alice")
                .backgroundUrl(null)
                .build();

        StepVerifier.create(userEventPublisher.publishBackgroundChanged(userWithNullBg))
                .verifyComplete();

        verify(kafka).send(eq("user.background.changed"), eq(userId.toString()), any(SpecificRecord.class));
    }

    // ── publishFollowed ───────────────────────────────────────────────────────

    @Test
    void publishFollowed_givenFollowerAndFollowing_sendsToFollowedTopic() {
        UUID followerId = UUID.randomUUID();
        UUID followingId = UUID.randomUUID();

        StepVerifier.create(userEventPublisher.publishFollowed(followerId, followingId, "bob"))
                .verifyComplete();

        verify(kafka).send(eq("user.followed"), eq(followerId.toString()), any(SpecificRecord.class));
    }

    @Test
    void publishFollowed_givenNullFollowerUsername_defaultsToEmptyString() {
        UUID followerId = UUID.randomUUID();
        UUID followingId = UUID.randomUUID();

        StepVerifier.create(userEventPublisher.publishFollowed(followerId, followingId, null))
                .verifyComplete();

        verify(kafka).send(eq("user.followed"), eq(followerId.toString()), any(SpecificRecord.class));
    }

    // ── publishUnfollowed ─────────────────────────────────────────────────────

    @Test
    void publishUnfollowed_givenFollowerAndFollowing_sendsToUnfollowedTopic() {
        UUID followerId = UUID.randomUUID();
        UUID followingId = UUID.randomUUID();

        StepVerifier.create(userEventPublisher.publishUnfollowed(followerId, followingId))
                .verifyComplete();

        verify(kafka).send(eq("user.unfollowed"), eq(followerId.toString()), any(SpecificRecord.class));
    }
}
