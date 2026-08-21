package com.kama.jchatmind.mcp;

import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.EmailTools;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Configuration
public class McpServerConfig {

    @Bean
    @Primary
    @org.springframework.beans.factory.annotation.Qualifier("mcpServerToolCallbackProvider")
    ToolCallbackProvider mcpToolCallbackProvider(
            McpKnowledgeTool mcpKnowledgeTool,
            EmailTools emailTools,
            DataBaseTools dataBaseTools
    ) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(mcpKnowledgeTool, emailTools, dataBaseTools)
                .build();
    }

    @Bean
    FilterRegistrationBean<McpApiKeyFilter> mcpApiKeyFilterRegistration(
            McpPrincipalAccessService mcpPrincipalAccessService
    ) {
        FilterRegistrationBean<McpApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpApiKeyFilter(mcpPrincipalAccessService));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static class McpApiKeyFilter implements jakarta.servlet.Filter {

        static final String CALLER_IDENTITY_ATTRIBUTE = "jchatmind.mcp.callerIdentity";
        static final String CORRELATION_ID_ATTRIBUTE = "jchatmind.mcp.correlationId";

        private final McpPrincipalAccessService mcpPrincipalAccessService;

        McpApiKeyFilter(McpPrincipalAccessService mcpPrincipalAccessService) {
            this.mcpPrincipalAccessService = mcpPrincipalAccessService;
        }

        @Override
        public void doFilter(
                jakarta.servlet.ServletRequest request,
                jakarta.servlet.ServletResponse response,
                jakarta.servlet.FilterChain chain
        ) throws IOException, jakarta.servlet.ServletException {
            jakarta.servlet.http.HttpServletRequest httpRequest = (jakarta.servlet.http.HttpServletRequest) request;
            String providedKey = httpRequest.getHeader("X-API-Key");
            String correlationId = UUID.randomUUID().toString();
            var caller = mcpPrincipalAccessService.resolveCaller(providedKey);
            if (caller.isPresent()) {
                httpRequest.setAttribute(CALLER_IDENTITY_ATTRIBUTE, caller.get());
                httpRequest.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
                mcpPrincipalAccessService.recordAuthentication(caller.get(), correlationId, "ALLOW", "authenticated");
                chain.doFilter(request, response);
                return;
            }
            mcpPrincipalAccessService.recordAuthentication(null, correlationId, "DENY", "invalid_credential");
            jakarta.servlet.http.HttpServletResponse httpResponse = (jakarta.servlet.http.HttpServletResponse) response;
            httpResponse.setStatus(401);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getOutputStream().write(
                    "{\"error\":\"Unauthorized\",\"message\":\"Invalid or missing X-API-Key header\"}"
                            .getBytes(StandardCharsets.UTF_8)
            );
        }
    }
}
