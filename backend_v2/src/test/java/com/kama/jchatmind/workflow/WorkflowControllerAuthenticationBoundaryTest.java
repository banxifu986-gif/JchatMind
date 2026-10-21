package com.kama.jchatmind.workflow;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.auth.TokenInterceptor;
import com.kama.jchatmind.config.WebConfig;
import com.kama.jchatmind.controller.WorkflowController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowControllerAuthenticationBoundaryTest {

    @Test
    void shouldRunJwtInterceptorForExplicitWorkflowRoute() throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(WorkflowWebConfiguration.class);
            context.refresh();
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

            mockMvc.perform(post("/api/workflows/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"agentId\":\"agent-1\",\"sessionId\":\"session-1\",\"request\":\"question\",\"sourceId\":\"build-system\"}"))
                    .andExpect(status().isOk());

            verify(context.getBean(TokenInterceptor.class)).preHandle(any(), any(), any());
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({WebConfig.class, WorkflowController.class})
    static class WorkflowWebConfiguration {

        @Bean
        TokenInterceptor tokenInterceptor(RequestScopeData requestScopeData) throws Exception {
            TokenInterceptor interceptor = mock(TokenInterceptor.class);
            when(interceptor.preHandle(any(), any(), any())).thenAnswer(invocation -> {
                requestScopeData.setUserId(7L);
                return true;
            });
            return interceptor;
        }

        @Bean
        RequestScopeData requestScopeData() {
            return new RequestScopeData();
        }

        @Bean
        WorkflowExecutionService workflowExecutionService() {
            WorkflowExecutionService service = mock(WorkflowExecutionService.class);
            when(service.execute(any(), any())).thenReturn(new WorkflowExecutionService.WorkflowExecutionResponse(
                    "trace-1", "WORKFLOW", null, null, java.util.List.of()
            ));
            return service;
        }
    }
}
