package com.kama.jchatmind.mcp;

import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.rag.RagRouter;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.RagService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpKnowledgeToolTest {

    @Test
    void shouldDenyPrivateKnowledgeRetrievalWithoutCallerIdentity() {
        RagService ragService = mock(RagService.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        McpKnowledgeTool tool = new McpKnowledgeTool(
                ragService,
                knowledgeBaseMapper,
                mock(KnowledgeBaseAccessService.class),
                mock(McpPrincipalAccessService.class)
        );

        String result = tool.search("私有知识", List.of("kb-private"));

        assertThat(result).isEqualTo("当前 MCP 调用未绑定用户身份，禁止访问私有知识库。");
        verifyNoInteractions(ragService, knowledgeBaseMapper);
    }

    @Test
    void shouldRetrieveOnlyKnowledgeBasesOwnedByResolvedCaller() {
        RagService ragService = mock(RagService.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        McpKnowledgeTool tool = new McpKnowledgeTool(
                ragService,
                knowledgeBaseMapper,
                knowledgeBaseAccessService,
                mock(McpPrincipalAccessService.class)
        );
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CALLER_IDENTITY_ATTRIBUTE))
                .thenReturn(new McpCallerIdentity(11L, 7L));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            RagRetrievalResult result = new RagRetrievalResult();
            result.setKbId("kb-own");
            result.setContent("仅属于用户 7 的内容");
            result.setMetadata("{\"sourceName\":\"owner.md\",\"contentPath\":\"# Owner\"}");
            when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("kb-own"), "7"))
                    .thenReturn(List.of("kb-own"));
            when(knowledgeBaseMapper.selectByIdBatch(List.of("kb-own")))
                    .thenReturn(List.of(KnowledgeBase.builder().id("kb-own").name("用户 7 知识库").build()));
            when(ragService.retrieve(List.of("kb-own"), "查询", 3)).thenReturn(List.of(result));

            String response = tool.search("查询", List.of("kb-own"));

            assertThat(response).contains("知识库: 用户 7 知识库").contains("仅属于用户 7 的内容");
            verify(knowledgeBaseAccessService).requireAccessibleKnowledgeBaseIds(List.of("kb-own"), "7");
            verify(ragService).retrieve(List.of("kb-own"), "查询", 3);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldAuditAllowedKnowledgeQueryWithRequestCorrelationId() {
        RagService ragService = mock(RagService.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        McpPrincipalAccessService principalAccessService = mock(McpPrincipalAccessService.class);
        McpKnowledgeTool tool = createAuditedTool(
                ragService,
                knowledgeBaseMapper,
                knowledgeBaseAccessService,
                principalAccessService
        );
        McpCallerIdentity caller = new McpCallerIdentity(11L, 7L);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CALLER_IDENTITY_ATTRIBUTE)).thenReturn(caller);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CORRELATION_ID_ATTRIBUTE)).thenReturn("request-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("kb-own"), "7"))
                    .thenReturn(List.of("kb-own"));
            when(ragService.retrieve(List.of("kb-own"), "查询", 3)).thenReturn(List.of());

            tool.search("查询", List.of("kb-own"));

            verify(principalAccessService).recordKnowledgeQuery(
                    caller,
                    "request-123",
                    "ALLOW",
                    List.of("kb-own"),
                    "retrieved"
            );
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldAuditDeniedKnowledgeQueryWithoutRetrievingForeignKnowledge() {
        RagService ragService = mock(RagService.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        McpPrincipalAccessService principalAccessService = mock(McpPrincipalAccessService.class);
        McpKnowledgeTool tool = createAuditedTool(
                ragService,
                mock(KnowledgeBaseMapper.class),
                knowledgeBaseAccessService,
                principalAccessService
        );
        McpCallerIdentity caller = new McpCallerIdentity(11L, 7L);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CALLER_IDENTITY_ATTRIBUTE)).thenReturn(caller);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CORRELATION_ID_ATTRIBUTE)).thenReturn("request-456");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("kb-foreign"), "7"))
                    .thenThrow(new BizException("无权访问知识库"));

            String response = tool.search("查询", List.of("kb-foreign"));

            assertThat(response).isEqualTo("当前 MCP 调用未绑定用户身份，禁止访问私有知识库。");
            verify(principalAccessService).recordKnowledgeQuery(
                    caller,
                    "request-456",
                    "DENY",
                    List.of("kb-foreign"),
                    "knowledge_base_access_denied"
            );
            verifyNoInteractions(ragService);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldAuditExternalLookupDeniedBeforePrivateRetrieval() {
        RagService ragService = mock(RagService.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        McpPrincipalAccessService principalAccessService = mock(McpPrincipalAccessService.class);
        McpKnowledgeTool tool = new McpKnowledgeTool(
                ragService,
                mock(KnowledgeBaseMapper.class),
                knowledgeBaseAccessService,
                principalAccessService,
                new RagRouter()
        );
        McpCallerIdentity caller = new McpCallerIdentity(11L, 7L);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CALLER_IDENTITY_ATTRIBUTE)).thenReturn(caller);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CORRELATION_ID_ATTRIBUTE)).thenReturn("request-external");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("kb-own"), "7"))
                    .thenReturn(List.of("kb-own"));

            String response = tool.search("查询最新官方文档", List.of("kb-own"));

            assertThat(response).contains("许可");
            verifyNoInteractions(ragService);
            verify(principalAccessService).recordKnowledgeQuery(
                    caller,
                    "request-external",
                    "DENY",
                    List.of("kb-own"),
                    "route_abstain"
            );
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldUseRouterRetrievalLimitAndKeepAuditForMultimodalMcpQuery() {
        RagService ragService = mock(RagService.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        McpPrincipalAccessService principalAccessService = mock(McpPrincipalAccessService.class);
        McpKnowledgeTool tool = new McpKnowledgeTool(
                ragService,
                mock(KnowledgeBaseMapper.class),
                knowledgeBaseAccessService,
                principalAccessService,
                new RagRouter()
        );
        McpCallerIdentity caller = new McpCallerIdentity(11L, 7L);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CALLER_IDENTITY_ATTRIBUTE))
                .thenReturn(caller);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CORRELATION_ID_ATTRIBUTE)).thenReturn("request-multimodal");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("kb-own"), "7"))
                    .thenReturn(List.of("kb-own"));
            when(ragService.retrieve(List.of("kb-own"), "请定位 PDF 第 2 页的表格", 5)).thenReturn(List.of());

            tool.search("请定位 PDF 第 2 页的表格", List.of("kb-own"));

            verify(ragService).retrieve(List.of("kb-own"), "请定位 PDF 第 2 页的表格", 5);
            verify(principalAccessService).recordKnowledgeQuery(
                    caller,
                    "request-multimodal",
                    "ALLOW",
                    List.of("kb-own"),
                    "retrieved"
            );
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldRefuseWhenMcpRetrievalHasNoEvidence() {
        RagService ragService = mock(RagService.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        McpKnowledgeTool tool = new McpKnowledgeTool(
                ragService,
                mock(KnowledgeBaseMapper.class),
                knowledgeBaseAccessService,
                mock(McpPrincipalAccessService.class),
                new RagRouter()
        );
        McpCallerIdentity caller = new McpCallerIdentity(11L, 7L);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CALLER_IDENTITY_ATTRIBUTE)).thenReturn(caller);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("kb-own"), "7"))
                    .thenReturn(List.of("kb-own"));
            when(ragService.retrieve(List.of("kb-own"), "查询", 3)).thenReturn(List.of());

            String response = tool.search("查询", List.of("kb-own"));

            assertThat(response).contains("没有足够证据");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldFormatStableCitationForMcpEvidence() {
        RagService ragService = mock(RagService.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        McpKnowledgeTool tool = new McpKnowledgeTool(
                ragService,
                knowledgeBaseMapper,
                knowledgeBaseAccessService,
                mock(McpPrincipalAccessService.class),
                new RagRouter()
        );
        McpCallerIdentity caller = new McpCallerIdentity(11L, 7L);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(McpServerConfig.McpApiKeyFilter.CALLER_IDENTITY_ATTRIBUTE)).thenReturn(caller);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            RagRetrievalResult evidence = new RagRetrievalResult();
            evidence.setChunkId("chunk-1");
            evidence.setKbId("kb-own");
            evidence.setContent("证据正文");
            evidence.setMetadata("{\"sourceName\":\"设计文档.pdf\",\"contentPath\":\"第 2 页\",\"pageNumber\":2}");
            when(knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(List.of("kb-own"), "7"))
                    .thenReturn(List.of("kb-own"));
            when(knowledgeBaseMapper.selectByIdBatch(List.of("kb-own")))
                    .thenReturn(List.of(KnowledgeBase.builder().id("kb-own").name("研发知识库").build()));
            when(ragService.retrieve(List.of("kb-own"), "查询", 3)).thenReturn(List.of(evidence));

            String response = tool.search("查询", List.of("kb-own"));

            assertThat(response).contains("引用: chunk-1").contains("页码: 2").contains("证据正文");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private McpKnowledgeTool createAuditedTool(
            RagService ragService,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            McpPrincipalAccessService principalAccessService
    ) {
        try {
            return McpKnowledgeTool.class.getConstructor(
                    RagService.class,
                    KnowledgeBaseMapper.class,
                    KnowledgeBaseAccessService.class,
                    McpPrincipalAccessService.class
            ).newInstance(ragService, knowledgeBaseMapper, knowledgeBaseAccessService, principalAccessService);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("McpKnowledgeTool 必须接收 McpPrincipalAccessService 以记录检索审计", e);
        }
    }
}
