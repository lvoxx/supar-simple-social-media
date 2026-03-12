package io.github.lvoxx.media_service.kafka;

import java.time.Instant;

import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
import org.springframework.stereotype.Component;

import io.github.lvoxx.common_core.util.UlidGenerator;
import io.github.lvoxx.media.MediaUploadCompletedEvent;
import io.github.lvoxx.media.MediaUploadFailedEvent;
import io.github.lvoxx.media_service.entity.MediaAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Publishes media domain events as Avro-serialised records to Kafka.
 *
 * <p>
 * Topics published:
 * <ul>
 * <li>{@code media.upload.completed} — asset đã qua CDN và sẵn sàng sử
 * dụng</li>
 * <li>{@code media.upload.failed} — xử lý hoặc kiểm duyệt thất bại</li>
 * </ul>
 *
 * <p>
 * Avro schema:
 * {@code common-core/src/main/avro/MediaUploadCompletedEvent.avsc},
 * {@code MediaUploadFailedEvent.avsc}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaEventPublisher {

        private static final String SERVICE = "media-service";

        private final ReactiveKafkaProducerTemplate<String, SpecificRecord> kafka;

        /**
         * Publishes {@link MediaUploadCompletedEvent} khi asset đã được upload lên CDN
         * thành công.
         *
         * @param asset entity {@link MediaAsset} đã chuyển sang trạng thái
         *              {@code READY}
         * @return {@link Mono} hoàn thành khi Kafka xác nhận
         */
        public Mono<Void> publishUploadCompleted(MediaAsset asset) {
                MediaUploadCompletedEvent event = MediaUploadCompletedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType("media.upload.completed")
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(SERVICE)
                                .setMediaId(asset.getId().toString())
                                .setOwnerId(asset.getOwnerId().toString())
                                .setOwnerType(asset.getOwnerType())
                                .setCdnUrl(asset.getCdnUrl() != null ? asset.getCdnUrl() : "")
                                .setThumbnailUrl(asset.getThumbnailUrl())
                                .setContentType(asset.getContentType())
                                .setStatus("READY")
                                .build();

                return send("media.upload.completed", asset.getId().toString(), event);
        }

        /**
         * Publishes {@link MediaUploadFailedEvent} khi xử lý hoặc kiểm duyệt nội dung
         * thất bại.
         *
         * @param asset  entity {@link MediaAsset} đã chuyển sang trạng thái
         *               {@code REJECTED}
         * @param reason lý do thất bại (dành cho logging và UX)
         * @return {@link Mono} hoàn thành khi Kafka xác nhận
         */
        public Mono<Void> publishUploadFailed(MediaAsset asset, String reason) {
                MediaUploadFailedEvent event = MediaUploadFailedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType("media.upload.failed")
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(SERVICE)
                                .setMediaId(asset.getId().toString())
                                .setOwnerId(asset.getOwnerId().toString())
                                .setReason(reason)
                                .build();

                return send("media.upload.failed", asset.getId().toString(), event);
        }

        private Mono<Void> send(String topic, String key, SpecificRecord record) {
                return kafka.send(topic, key, record)
                                .doOnSuccess(r -> log.info("Published Avro event topic={} key={} schema={}",
                                                topic, key, record.getSchema().getName()))
                                .doOnError(e -> log.error("Failed to publish Avro event topic={} key={}: {}",
                                                topic, key, e.getMessage()))
                                .then();
        }
}
