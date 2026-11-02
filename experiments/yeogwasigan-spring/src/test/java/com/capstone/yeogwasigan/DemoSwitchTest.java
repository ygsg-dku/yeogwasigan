package com.capstone.yeogwasigan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone.yeogwasigan.gateway.GatewayService;
import com.capstone.yeogwasigan.web.DemoSwitchController;
import com.capstone.yeogwasigan.web.GatewayController;
import com.capstone.yeogwasigan.web.SecurityConfig;

/** (계정은 application.yml 의 yeogwasigan.gateway.users) 시연 전용 사용자 전환: 같은 컴퓨터에서만, 등록된 계정으로만, 전환 뒤에는 그 계정으로 로그인된 상태. */
@WebMvcTest({DemoSwitchController.class, GatewayController.class})
@Import(SecurityConfig.class)
@ActiveProfiles("demo")
class DemoSwitchTest {

    @Autowired MockMvc mvc;
    @MockitoBean GatewayService gateway;

    @Test
    void 전환하면_그_계정으로_게이트웨이를_쓴다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/demo/switch").param("user", "sec-park").session(session))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/gateway.html"));
        mvc.perform(get("/api/gateway/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("sec-park"));
    }

    @Test
    void 시작_주소는_요청자만_가진_계정으로_연다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/demo").session(session)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/gateway/me").session(session)).andExpect(jsonPath("$.user").value("ops-kim"));
    }

    @Test
    void 다른_컴퓨터에서_오거나_없는_계정이면_막는다() throws Exception {
        mvc.perform(get("/demo/switch").param("user", "sec-park").with(r -> { r.setRemoteAddr("10.0.0.5"); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(get("/demo/switch").param("user", "nobody")).andExpect(status().isNotFound());
    }
}
