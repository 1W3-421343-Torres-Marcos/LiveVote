package com.livevote.votes.security;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livevote.votes.config.SecurityConfig;
import com.livevote.votes.config.SecurityProperties;
import com.livevote.votes.controller.PollController;
import com.livevote.votes.service.PollService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PollController.class)
@Import({SecurityConfig.class, AuthenticationFilter.class})
@EnableConfigurationProperties(SecurityProperties.class)
@TestPropertySource(properties = "app.security.trust-gateway-header=true")
class GatewayHeaderAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PollService pollService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void shouldAuthenticateUsingGatewayHeaderWhenTrusted() throws Exception {
        UUID userId = UUID.randomUUID();
        when(pollService.getMyPolls(userId)).thenReturn(List.of());

        mockMvc.perform(get("/api/polls/mine").header(AuthenticationFilter.HEADER_USER_ID, userId.toString()))
                .andExpect(status().isOk());

        verify(pollService).getMyPolls(userId);
    }

    @Test
    void shouldRejectInvalidOrMissingGatewayHeader() throws Exception {
        mockMvc.perform(get("/api/polls/mine"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/polls/mine").header(AuthenticationFilter.HEADER_USER_ID, "not-a-uuid"))
                .andExpect(status().isUnauthorized());
    }
}
