package com.capstone.yeogwasigan;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone.yeogwasigan.gateway.GatewayRequest;
import com.capstone.yeogwasigan.gateway.GatewayService;
import com.capstone.yeogwasigan.web.GatewayController;
import com.capstone.yeogwasigan.web.SecurityConfig;

/** 게이트웨이 로그인·CSRF 규칙. 서비스는 가짜(mock)라 저장·전송은 하지 않는다. */
@WebMvcTest(GatewayController.class)
@Import(SecurityConfig.class)
class GatewaySecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean GatewayService gateway;

    @Test
    void 로그인하지_않으면_API는_401이고_화면은_로그인으로_보낸다() throws Exception {
        mvc.perform(get("/api/requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/gateway.html").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "ops-kim", roles = "REQUESTER")
    void CSRF_토큰_없이_요청을_만들면_403() throws Exception {
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content("{\"log\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "ops-kim", roles = "REQUESTER")
    void 로그인하고_CSRF_토큰이_있으면_요청을_만들_수_있다() throws Exception {
        GatewayRequest r = mock(GatewayRequest.class);
        when(r.getPayload()).thenReturn(List.of());
        when(r.getWarnings()).thenReturn(List.of());
        when(r.getDroppedFields()).thenReturn(List.of());
        when(gateway.create(any(), any(), any(), any())).thenReturn(r);
        mvc.perform(post("/api/requests").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"log\":\"x\"}"))
                .andExpect(status().isCreated());
    }
}
