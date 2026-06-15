package com.lvoxx.sssm.post.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvoxx.sssm.post.config.SecurityConfig;
import com.lvoxx.sssm.post.security.GatewayAuthenticationFilter;
import com.lvoxx.sssm.post.service.EngagementService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for the engagement endpoints: every engagement write requires the gateway-forwarded
 * identity (401 without it) and returns 204 with it.
 */
@WebMvcTest(EngagementController.class)
@Import(SecurityConfig.class)
class EngagementControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EngagementService engagements;

    @Test
    void like_withoutGatewayIdentity_returns401() throws Exception {
        mvc.perform(put("/api/v1/posts/" + UUID.randomUUID() + "/like"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void like_withGatewayIdentity_returns204() throws Exception {
        mvc.perform(put("/api/v1/posts/" + UUID.randomUUID() + "/like")
                        .header(GatewayAuthenticationFilter.SUBJECT_HEADER,
                                UUID.randomUUID().toString())
                        .header(GatewayAuthenticationFilter.ROLES_HEADER, "user"))
                .andExpect(status().isNoContent());
    }

    @Test
    void unbookmark_withGatewayIdentity_returns204() throws Exception {
        mvc.perform(delete("/api/v1/posts/" + UUID.randomUUID() + "/bookmark")
                        .header(GatewayAuthenticationFilter.SUBJECT_HEADER,
                                UUID.randomUUID().toString())
                        .header(GatewayAuthenticationFilter.ROLES_HEADER, "user"))
                .andExpect(status().isNoContent());
    }

    @Test
    void repost_withoutGatewayIdentity_returns401() throws Exception {
        mvc.perform(put("/api/v1/posts/" + UUID.randomUUID() + "/repost"))
                .andExpect(status().isUnauthorized());
    }
}
