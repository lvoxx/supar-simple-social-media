package com.lvoxx.sssm.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lvoxx.sssm.media.domain.Media;
import com.lvoxx.sssm.media.domain.MediaStatus;
import com.lvoxx.sssm.media.error.BadRequestException;
import com.lvoxx.sssm.media.error.ForbiddenException;
import com.lvoxx.sssm.media.repository.MediaRepository;
import com.lvoxx.sssm.media.service.StorageService.HeadResult;
import com.lvoxx.sssm.media.service.StorageService.PresignedUpload;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the media lifecycle: content-type validation, presign + PENDING reservation,
 * owner-scoped completion (HEAD → READY) and deletion, and the rejection paths.
 */
@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    private MediaRepository repo;
    @Mock
    private StorageService storage;
    @Mock
    private ImgproxyUrlBuilder imgproxy;
    @InjectMocks
    private MediaService service;

    private final UUID owner = UUID.randomUUID();

    @Test
    void createUploadTicket_reservesPendingRowAndPresigns() {
        when(repo.saveAndFlush(any(Media.class))).thenAnswer(inv -> inv.getArgument(0));
        when(storage.presignUpload(anyString(), anyString()))
                .thenReturn(new PresignedUpload("http://r2/put", Instant.now().plusSeconds(900)));

        MediaService.UploadTicket ticket = service.createUploadTicket(owner, "image/png");

        assertThat(ticket.uploadUrl()).isEqualTo("http://r2/put");
        assertThat(ticket.objectKey()).startsWith("media/" + owner + "/").endsWith(".png");
        verify(repo).saveAndFlush(any(Media.class));
    }

    @Test
    void createUploadTicket_rejectsUnsupportedContentType() {
        assertThatThrownBy(() -> service.createUploadTicket(owner, "application/pdf"))
                .isInstanceOf(BadRequestException.class);
        verify(repo, never()).saveAndFlush(any());
        verify(storage, never()).presignUpload(anyString(), anyString());
    }

    @Test
    void completeUpload_headsObjectAndMarksReady() {
        Media media = new Media(owner, "media/o/x.jpg", "image/jpeg");
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(media));
        when(storage.head("media/o/x.jpg")).thenReturn(new HeadResult("image/jpeg", 2048L));

        Media result = service.completeUpload(owner, id, 800, 600);

        assertThat(result.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(result.getSizeBytes()).isEqualTo(2048L);
        assertThat(result.getWidth()).isEqualTo(800);
    }

    @Test
    void completeUpload_rejectsNonOwner() {
        Media media = new Media(owner, "media/o/x.jpg", "image/jpeg");
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.completeUpload(UUID.randomUUID(), id, null, null))
                .isInstanceOf(ForbiddenException.class);
        verify(storage, never()).head(anyString());
    }

    @Test
    void delete_removesRowAndObjectForOwner() {
        Media media = new Media(owner, "media/o/x.jpg", "image/jpeg");
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(media));

        service.delete(owner, id);

        verify(repo).delete(media);
        verify(storage).delete("media/o/x.jpg");
    }

    @Test
    void variantsOf_isEmptyUntilReady() {
        Media pending = new Media(owner, "media/o/x.jpg", "image/jpeg");

        assertThat(service.variantsOf(pending)).isEmpty();
        verify(imgproxy, never()).variantsFor(anyString());
    }
}
