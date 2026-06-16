package com.lvoxx.sssm.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.lvoxx.sssm.media.domain.Media;
import com.lvoxx.sssm.media.domain.MediaStatus;
import com.lvoxx.sssm.media.repository.MediaRepository;
import com.lvoxx.sssm.media.service.ImgproxyUrlBuilder;
import com.lvoxx.sssm.media.service.MediaService;
import com.lvoxx.sssm.media.service.StorageService;
import com.lvoxx.sssm.media.service.StorageService.HeadResult;
import com.lvoxx.sssm.media.service.StorageService.PresignedUpload;
import com.lvoxx.sssm.media.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration test of the media flow against a real PostgreSQL: reserving a PENDING row, then
 * confirming the upload to promote it to READY. R2 is mocked ({@link StorageService}) so no object
 * store is contacted — the point is to prove the {@link Media} entity round-trips through the
 * infrastructure-owned schema with {@code ddl-auto=validate}.
 */
class MediaFlowIT extends PostgresIntegrationTest {

    @Autowired
    private MediaService service;
    @Autowired
    private MediaRepository repo;

    @MockitoBean
    private StorageService storage;
    @MockitoBean
    private ImgproxyUrlBuilder imgproxy;

    @Test
    void uploadTicketThenComplete_persistsAndPromotesToReady() {
        UUID owner = UUID.randomUUID();
        when(storage.presignUpload(anyString(), anyString()))
                .thenReturn(new PresignedUpload("http://r2/put", Instant.now().plusSeconds(900)));

        MediaService.UploadTicket ticket = service.createUploadTicket(owner, "image/jpeg");

        Media pending = repo.findById(ticket.mediaId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(MediaStatus.PENDING);
        assertThat(pending.getOwnerId()).isEqualTo(owner);

        when(storage.head(ticket.objectKey())).thenReturn(new HeadResult("image/jpeg", 4096L));
        when(imgproxy.variantsFor(anyString())).thenReturn(List.of());

        service.completeUpload(owner, ticket.mediaId(), 1024, 768);

        Media ready = repo.findById(ticket.mediaId()).orElseThrow();
        assertThat(ready.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(ready.getSizeBytes()).isEqualTo(4096L);
        assertThat(ready.getWidth()).isEqualTo(1024);
        assertThat(ready.getHeight()).isEqualTo(768);
    }
}
