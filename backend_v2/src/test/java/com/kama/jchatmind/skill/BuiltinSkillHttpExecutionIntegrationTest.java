package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.auth.TokenInterceptor;
import com.kama.jchatmind.config.WebConfig;
import com.kama.jchatmind.controller.BuiltinSkillController;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BuiltinSkillHttpExecutionIntegrationTest {

    private static final String JWT_SECRET =
            "jchatmind-skill-flow-test-secret-value-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final String ENDPOINT =
            "/api/agents/agent-1/skills/technical-decision-comparison/execute";

    @Test
    void executesTheSkillHttpFlowWithOnlyTheAuthenticatedOwnersNarrowedKnowledgeScope() throws Exception {
        try (AnnotationConfigWebApplicationContext context = newWebContext()) {
            AgentMapper agentMapper = context.getBean(AgentMapper.class);
            ChatSessionFacadeService sessions = context.getBean(ChatSessionFacadeService.class);
            AgentKnowledgeBaseBindingService bindings = context.getBean(AgentKnowledgeBaseBindingService.class);
            KnowledgeBaseAccessService access = context.getBean(KnowledgeBaseAccessService.class);
            SkillToolProbe toolProbe = context.getBean(SkillToolProbe.class);
            when(sessions.getChatSession("session-1", "42")).thenReturn(ownedSession());
            when(agentMapper.selectById("agent-1")).thenReturn(ownedAgent());
            when(bindings.getBoundKnowledgeBaseIds("agent-1")).thenReturn(List.of("kb-1", "kb-2"));
            when(access.requireAccessibleKnowledgeBaseIds(List.of("kb-1", "kb-2"), "42"))
                    .thenReturn(List.of("kb-1", "kb-2"));
            String token = context.getBean(JwtUtil.class).generateToken(42L);
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

            mockMvc.perform(post(ENDPOINT)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                    "sessionId", "session-1",
                                    "input", Map.of("question", "比较方案", "kbIds", List.of("kb-2"))
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.valid").value(true))
                    .andExpect(jsonPath("$.data.output.evidence[0].chunkId").value("chunk-2"))
                    .andExpect(jsonPath("$.data.output.evidence[0].kbId").value("kb-2"));

            assertThat(toolProbe.userId.get()).isEqualTo("42");
            assertThat(toolProbe.sessionId.get()).isEqualTo("session-1");
            assertThat(toolProbe.question.get()).isEqualTo("比较方案");
            assertThat(toolProbe.knowledgeBaseIds.get()).containsExactly("kb-2");
            verify(access).requireAccessibleKnowledgeBaseIds(List.of("kb-1", "kb-2"), "42");
        }
    }

    @Test
    void rejectsAnHttpRequestThatTriesToWidenKnowledgeScopeBeforeToolExecution() throws Exception {
        try (AnnotationConfigWebApplicationContext context = newWebContext()) {
            AgentMapper agentMapper = context.getBean(AgentMapper.class);
            ChatSessionFacadeService sessions = context.getBean(ChatSessionFacadeService.class);
            AgentKnowledgeBaseBindingService bindings = context.getBean(AgentKnowledgeBaseBindingService.class);
            KnowledgeBaseAccessService access = context.getBean(KnowledgeBaseAccessService.class);
            SkillToolProbe toolProbe = context.getBean(SkillToolProbe.class);
            when(sessions.getChatSession("session-1", "42")).thenReturn(ownedSession());
            when(agentMapper.selectById("agent-1")).thenReturn(ownedAgent());
            when(bindings.getBoundKnowledgeBaseIds("agent-1")).thenReturn(List.of("kb-1", "kb-2"));
            when(access.requireAccessibleKnowledgeBaseIds(List.of("kb-1", "kb-2"), "42"))
                    .thenReturn(List.of("kb-1", "kb-2"));
            String token = context.getBean(JwtUtil.class).generateToken(42L);
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

            mockMvc.perform(post(ENDPOINT)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                    "sessionId", "session-1",
                                    "input", Map.of("question", "比较方案", "kbIds", List.of("kb-outside"))
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500));

            assertThat(toolProbe.userId.get()).isNull();
            assertThat(toolProbe.sessionId.get()).isNull();
            assertThat(toolProbe.question.get()).isNull();
            assertThat(toolProbe.knowledgeBaseIds.get()).isNull();
        }
    }

    private AnnotationConfigWebApplicationContext newWebContext() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "skill-flow-test-jwt",
                Map.of("jwt.secret", JWT_SECRET, "jwt.expiration", "3600")
        ));
        context.register(SkillHttpExecutionConfiguration.class);
        context.refresh();
        return context;
    }

    private GetChatSessionResponse ownedSession() {
        return GetChatSessionResponse.builder()
                .chatSession(ChatSessionVO.builder().id("session-1").agentId("agent-1").build())
                .build();
    }

    private Agent ownedAgent() {
        return Agent.builder()
                .id("agent-1")
                .userId("42")
                .allowedTools("[\"KnowledgeTool\"]")
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({
            RequestScopeData.class,
            JwtUtil.class,
            TokenInterceptor.class,
            WebConfig.class,
            BuiltinSkillController.class,
            GlobalExceptionHandler.class,
            BuiltinSkillRegistry.class,
            BuiltinSkillExecutor.class,
            BuiltinSkillExecutionService.class
    })
    static class SkillHttpExecutionConfiguration {

        @Bean
        AgentMapper agentMapper() {
            return mock(AgentMapper.class);
        }

        @Bean
        ChatSessionFacadeService chatSessionFacadeService() {
            return mock(ChatSessionFacadeService.class);
        }

        @Bean
        AgentKnowledgeBaseBindingService agentKnowledgeBaseBindingService() {
            return mock(AgentKnowledgeBaseBindingService.class);
        }

        @Bean
        KnowledgeBaseAccessService knowledgeBaseAccessService() {
            return mock(KnowledgeBaseAccessService.class);
        }

        @Bean
        SkillToolProbe skillToolProbe() {
            return new SkillToolProbe();
        }

        @Bean
        SkillKnowledgeToolExecutor skillKnowledgeToolExecutor(SkillToolProbe probe) {
            return (userId, sessionId, question, knowledgeBaseIds) -> {
                probe.userId.set(userId);
                probe.sessionId.set(sessionId);
                probe.question.set(question);
                probe.knowledgeBaseIds.set(List.copyOf(knowledgeBaseIds));
                RagRetrievalResult result = new RagRetrievalResult();
                result.setChunkId("chunk-2");
                result.setKbId("kb-2");
                return SkillKnowledgeToolResult.executed(List.of(result));
            };
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    static class SkillToolProbe {

        private final AtomicReference<String> userId = new AtomicReference<>();
        private final AtomicReference<String> sessionId = new AtomicReference<>();
        private final AtomicReference<String> question = new AtomicReference<>();
        private final AtomicReference<List<String>> knowledgeBaseIds = new AtomicReference<>();
    }
}
