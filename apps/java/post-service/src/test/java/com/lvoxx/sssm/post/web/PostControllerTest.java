package com.lvoxx.sssm.post.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvoxx.sssm.post.config.SecurityConfig;
import com.lvoxx.sssm.post.domain.Post;
import com.lvoxx.sssm.post.security.GatewayAuthenticationFilter;
import com.lvoxx.sssm.post.service.PostService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test proving the gateway-trusted security wiring: reads are public, writes require the
 * gateway-forwarded identity header, and a present header authenticates the caller.
 */
@WebMvcTest(PostController.class)
@Import(SecurityConfig.class)
class PostControllerTest {

    private static final String BODY = "{\"text\":\"hello\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PostService posts;

    @Test
    void getById_isPublic() throws Exception {
        when(posts.getById(any(UUID.class)))
                .thenReturn(new Post(UUID.randomUUID(), "hello", null));

        mvc.perform(get("/api/v1/posts/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("hello"));
    }

    @Test
    void create_withoutGatewayIdentity_returns401() throws Exception {
        mvc.perform(post("/api/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withGatewayIdentity_returns201() throws Exception {
        when(posts.create(any(UUID.class), any()))
                .thenReturn(new Post(UUID.randomUUID(), "hello", null));

        mvc.perform(post("/api/v1/posts")
                        .header(GatewayAuthenticationFilter.SUBJECT_HEADER,
                                UUID.randomUUID().toString())
                        .header(GatewayAuthenticationFilter.ROLES_HEADER, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("hello"));
    }

    @Test
    void delete_withoutGatewayIdentity_returns401() throws Exception {
        mvc.perform(delete("/api/v1/posts/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
