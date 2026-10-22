package com.kama.jchatmind.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.auth.TokenInterceptor;
import com.kama.jchatmind.config.WebConfig;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.skill.BuiltinSkillExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BuiltinSkillControllerAuthenticationBoundaryTest {

    private static final String JWT_SECRET =
            "jchatmind-skill-http-test-secret-value-0123456789-abcdefghijklmnopqrstuvwxyz";

    @Test
    void passesTheUserFromTheActualJwtInterceptorToTheSkillService() throws Exception {
        try (AnnotationConfigWebApplicationContext context = newWebContext()) {
            BuiltinSkillExecutionService executionService = context.getBean(BuiltinSkillExecutionService.class);
            Map<String, Object> input = Map.of("question", "比较方案", "kbIds", List.of("kb-2"));
            when(executionService.execute(
                    42L, "agent-1", "session-1", "technical-decision-comparison", input
            )).thenReturn(new BuiltinSkillExecutionService.ExecutionResponse(
                    Map.of("abstained", true, "reason", "no evidence"), true, List.of()
            ));
            String token = context.getBean(JwtUtil.class).generateToken(42L);
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

            mockMvc.perform(post("/api/agents/agent-1/skills/technical-decision-comparison/execute")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                    "sessionId", "session-1",
                                    "input", input
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.valid").value(true));

            verify(executionService).execute(
                    42L, "agent-1", "session-1", "technical-decision-comparison", input
            );
        }
    }

    @Test
    void doesNotCallSkillServiceWhenJwtIsMissing() throws Exception {
        try (AnnotationConfigWebApplicationContext context = newWebContext()) {
            BuiltinSkillExecutionService executionService = context.getBean(BuiltinSkillExecutionService.class);
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

            mockMvc.perform(post("/api/agents/agent-1/skills/technical-decision-comparison/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sessionId\":\"session-1\",\"input\":{\"question\":\"比较方案\"}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("用户未登录"));

            verifyNoInteractions(executionService);
        }
    }

    private AnnotationConfigWebApplicationContext newWebContext() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "skill-http-test-jwt",
                Map.of("jwt.secret", JWT_SECRET, "jwt.expiration", "3600")
        ));
        context.register(SkillHttpConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({
            RequestScopeData.class,
            JwtUtil.class,
            TokenInterceptor.class,
            WebConfig.class,
            BuiltinSkillController.class,
            GlobalExceptionHandler.class
    })
    static class SkillHttpConfiguration {

        @Bean
        BuiltinSkillExecutionService builtinSkillExecutionService() {
            return mock(BuiltinSkillExecutionService.class);
        }
    }
}
