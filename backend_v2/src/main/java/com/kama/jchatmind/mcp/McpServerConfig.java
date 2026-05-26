package com.kama.jchatmind.mcp;

import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.EmailTools;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class McpServerConfig {

    @Value("${mcp.api-key:}")
    private String mcpApiKey;

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
    FilterRegistrationBean<McpApiKeyFilter> mcpApiKeyFilterRegistration() {
        FilterRegistrationBean<McpApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpApiKeyFilter(mcpApiKey));
        registration.addUrlPatterns("/mcp/*");
        registration.setOrder(1);
        return registration;
    }

    static class McpApiKeyFilter implements jakarta.servlet.Filter {

        private final String apiKey;

        McpApiKeyFilter(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public void doFilter(
                jakarta.servlet.ServletRequest request,
                jakarta.servlet.ServletResponse response,
                jakarta.servlet.FilterChain chain
        ) throws IOException, jakarta.servlet.ServletException {
            if (!StringUtils.hasText(apiKey)) {
                chain.doFilter(request, response);
                return;
            }
            jakarta.servlet.http.HttpServletRequest httpRequest = (jakarta.servlet.http.HttpServletRequest) request;
            String providedKey = httpRequest.getHeader("X-API-Key");
            if (apiKey.equals(providedKey)) {
                chain.doFilter(request, response);
                return;
            }
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
