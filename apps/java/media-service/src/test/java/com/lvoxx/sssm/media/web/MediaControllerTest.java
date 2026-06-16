package com.lvoxx.sssm.media.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvoxx.sssm.media.config.SecurityConfig;
import com.lvoxx.sssm.media.domain.Media;
import com.lvoxx.sssm.media.security.GatewayAuthenticationFilter;
import com.lvoxx.sssm.media.service.MediaService;
import com.lvoxx.sssm.media.service.MediaService.UploadTicket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test proving the gateway-trusted security wiring: reading media is public, requesting an
 * upload requires the gateway-forwarded identity header, and a present header authenticates the
 * caller.
 */
@WebMvcTest(MediaController.class)
@Import(SecurityConfig.class)
class MediaControllerTest {

    private static final String UPLOAD_BODY = "{\"contentType\":\"image/png\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MediaService media;

    @Test
    void getById_isPublic() throws Exception {
        UUID id = UUID.randomUUID();
        when(media.getById(any(UUID.class)))
                .thenReturn(new Media(UUID.randomUUID(), "media/o/x.png", "image/png"));
        when(media.variantsOf(any())).thenReturn(List.of());

        mvc.perform(get("/api/v1/media/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void requestUpload_withoutGatewayIdentity_returns401() throws Exception {
        mvc.perform(post("/api/v1/media/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPLOAD_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestUpload_withGatewayIdentity_returns201() throws Exception {
        UUID mediaId = UUID.randomUUID();
        when(media.createUploadTicket(any(UUID.class), eq("image/png")))
                .thenReturn(new UploadTicket(
                        mediaId, "http://r2/put", "media/o/x.png", Instant.now().plusSeconds(900)));

        mvc.perform(post("/api/v1/media/uploads")
                        .header(GatewayAuthenticationFilter.SUBJECT_HEADER,
                                UUID.randomUUID().toString())
                        .header(GatewayAuthenticationFilter.ROLES_HEADER, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPLOAD_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").value("http://r2/put"))
                .andExpect(jsonPath("$.mediaId").value(mediaId.toString()));
    }

    @Test
    void delete_withoutGatewayIdentity_returns401() throws Exception {
        mvc.perform(delete("/api/v1/media/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
