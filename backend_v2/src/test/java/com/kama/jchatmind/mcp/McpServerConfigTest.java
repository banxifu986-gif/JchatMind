package com.kama.jchatmind.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpServerConfigTest {

    @Test
    void shouldConfigureStreamableHttpProtocol() throws Exception {
        String application = Files.readString(Path.of("src", "main", "resources", "application.yaml"));

        org.assertj.core.api.Assertions.assertThat(application)
                .contains("protocol: STREAMABLE");
    }

    @Test
    void shouldProtectTheStreamableHttpMcpRootEndpoint() {
        McpServerConfig config = new McpServerConfig();

        FilterRegistrationBean<McpServerConfig.McpApiKeyFilter> registration = config
                .mcpApiKeyFilterRegistration(mock(McpPrincipalAccessService.class));

        org.assertj.core.api.Assertions.assertThat(registration.getUrlPatterns())
                .contains("/mcp", "/mcp/*");
        org.assertj.core.api.Assertions.assertThat(registration.getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void shouldAttachResolvedPrincipalToTheMcpRequest() throws Exception {
        McpCallerIdentity caller = new McpCallerIdentity(11L, 7L);
        McpPrincipalAccessService principalAccessService = mock(McpPrincipalAccessService.class);
        when(principalAccessService.resolveCaller("mcp-test-key")).thenReturn(Optional.of(caller));
        McpServerConfig.McpApiKeyFilter filter = new McpServerConfig.McpApiKeyFilter(principalAccessService);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-API-Key")).thenReturn("mcp-test-key");

        filter.doFilter(request, response, chain);

        verify(request).setAttribute("jchatmind.mcp.callerIdentity", caller);
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldAuditAuthenticatedMcpRequestWithCorrelationId() throws Exception {
        McpCallerIdentity caller = new McpCallerIdentity(11L, 7L);
        McpPrincipalAccessService principalAccessService = mock(McpPrincipalAccessService.class);
        when(principalAccessService.resolveCaller("mcp-test-key")).thenReturn(Optional.of(caller));
        McpServerConfig.McpApiKeyFilter filter = new McpServerConfig.McpApiKeyFilter(principalAccessService);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-API-Key")).thenReturn("mcp-test-key");

        filter.doFilter(request, response, chain);

        verify(principalAccessService).recordAuthentication(
                eq(caller),
                anyString(),
                eq("ALLOW"),
                eq("authenticated")
        );
    }

    @Test
    void shouldAuditInvalidCredentialAndBlockMcpRequest() throws Exception {
        McpPrincipalAccessService principalAccessService = mock(McpPrincipalAccessService.class);
        when(principalAccessService.resolveCaller(null)).thenReturn(Optional.empty());
        McpServerConfig.McpApiKeyFilter filter = new McpServerConfig.McpApiKeyFilter(principalAccessService);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        ServletOutputStream outputStream = mock(ServletOutputStream.class);
        FilterChain chain = mock(FilterChain.class);
        when(response.getOutputStream()).thenReturn(outputStream);

        filter.doFilter(request, response, chain);

        verify(principalAccessService).recordAuthentication(
                isNull(),
                anyString(),
                eq("DENY"),
                eq("invalid_credential")
        );
        verify(response).setStatus(401);
        verifyNoInteractions(chain);
    }
}
