package com.lvoxx.sssm.user.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lvoxx.sssm.user.config.SecurityConfig;
import com.lvoxx.sssm.user.domain.Profile;
import com.lvoxx.sssm.user.security.GatewayAuthenticationFilter;
import com.lvoxx.sssm.user.service.ProfileService;
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
@WebMvcTest(ProfileController.class)
@Import(SecurityConfig.class)
class ProfileControllerTest {

    private static final String BODY = "{\"username\":\"alice\",\"displayName\":\"Alice\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProfileService profiles;

    @Test
    void getByUsername_isPublic() throws Exception {
        when(profiles.getByUsername("alice"))
                .thenReturn(new Profile(UUID.randomUUID(), "alice", "Alice"));

        mvc.perform(get("/api/v1/profiles/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void create_withoutGatewayIdentity_returns401() throws Exception {
        mvc.perform(post("/api/v1/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_withGatewayIdentity_returns201() throws Exception {
        when(profiles.create(any(UUID.class), any()))
                .thenReturn(new Profile(UUID.randomUUID(), "alice", "Alice"));

        mvc.perform(post("/api/v1/profiles")
                        .header(GatewayAuthenticationFilter.SUBJECT_HEADER,
                                UUID.randomUUID().toString())
                        .header(GatewayAuthenticationFilter.ROLES_HEADER, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void me_withoutGatewayIdentity_returns401() throws Exception {
        mvc.perform(get("/api/v1/profiles/me"))
                .andExpect(status().isUnauthorized());
    }
}
