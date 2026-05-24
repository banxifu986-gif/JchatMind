package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.converter.ChatSessionConverter;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.ChatSessionFacadeServiceImpl;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SpringBootTest(classes = RagSessionOnlineE2eEvaluationTest.RagSessionOnlineE2eTestConfig.class)
@ActiveProfiles("rag-eval")
class RagSessionOnlineE2eEvaluationTest {

    private static final int ONLINE_TOP_K = 3;
    private static final int CASE_LIMIT = 4;
    private static final int MAX_QUERY_LENGTH = 80;
    private static final String FOLLOW_UP_QUERY = "这部分该怎么展开";

    @Autowired
    private RagService ragService;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatSessionFacadeService chatSessionFacadeService;

    @Autowired
    private KnowledgeTools knowledgeTools;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${rag.eval.real-kb-id:}")
    private String realKbId;

    private Path reportOutputPath;

    @BeforeEach
    void setUp() throws IOException {
        reportOutputPath = Path.of("target", "rag-eval", "session-online-e2e-report.json");
        Files.createDirectories(reportOutputPath.getParent());
    }

    @Test
    void evaluateSessionAwareOnlineRetrieval() throws Exception {
        Assumptions.assumeTrue(StringUtils.hasText(realKbId), "缺少 rag.eval.real-kb-id，跳过多轮会话 RAG E2E 评测");

        List<SourceChunk> chunks = loadSourceChunks(realKbId);
        List<SessionQueryCase> cases = buildSessionCases(realKbId, chunks);
        Assumptions.assumeTrue(!cases.isEmpty(), "没有可用的多轮会话样本，跳过多轮会话 RAG E2E 评测");

        List<SessionEvaluatedCase> evaluatedCases = new ArrayList<>();
        for (SessionQueryCase queryCase : cases) {
            String chatSessionId = createEvaluationChatSession();
            try {
                KnowledgeTools sessionKnowledgeTools = knowledgeTools.fork(
                        "rag-eval-user",
                        chatSessionId,
                        List.of(com.kama.jchatmind.model.dto.KnowledgeBaseDTO.builder().id(realKbId).name(realKbId).build())
                );
                sessionKnowledgeTools.knowledgeQuery(queryCase.seedQuery(), List.of(realKbId));

                RagRetrievalContext sessionContext = chatSessionFacadeService.getRetrievalContext("rag-eval-user", chatSessionId);
                List<RetrievedChunk> statelessTopChunks = ragService.retrieve(List.of(realKbId), queryCase.followUpQuery(), ONLINE_TOP_K).stream()
                        .map(this::toRetrievedChunk)
                        .toList();
                List<RetrievedChunk> contextualTopChunks = ragService.retrieve(List.of(realKbId), queryCase.followUpQuery(), sessionContext, ONLINE_TOP_K).stream()
                        .map(this::toRetrievedChunk)
                        .toList();

                evaluatedCases.add(new SessionEvaluatedCase(
                        queryCase.id(),
                        queryCase.type(),
                        queryCase.seedQuery(),
                        queryCase.followUpQuery(),
                        queryCase.expectedSourceName(),
                        queryCase.expectedContentPath(),
                        queryCase.expectedChunkId(),
                        sessionContext,
                        statelessTopChunks,
                        contextualTopChunks,
                        hitAt(statelessTopChunks, queryCase.expectedChunkId(), queryCase.expectedContentPath(), 1),
                        hitAt(statelessTopChunks, queryCase.expectedChunkId(), queryCase.expectedContentPath(), ONLINE_TOP_K),
                        hitAt(contextualTopChunks, queryCase.expectedChunkId(), queryCase.expectedContentPath(), 1),
                        hitAt(contextualTopChunks, queryCase.expectedChunkId(), queryCase.expectedContentPath(), ONLINE_TOP_K)
                ));
            } finally {
                chatSessionMapper.deleteById(chatSessionId);
            }
        }

        SessionOnlineE2eReport report = buildReport(evaluatedCases);
        String jsonReport = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.writeString(reportOutputPath, jsonReport);
        System.out.println(jsonReport);
    }

    private List<SourceChunk> loadSourceChunks(String kbId) {
        return chunkBgeM3Mapper.selectTitlePathCandidatesByKbIds(List.of(kbId)).stream()
                .map(this::toSourceChunk)
                .filter(SourceChunk::usableForSessionEvaluation)
                .toList();
    }

    private SourceChunk toSourceChunk(RagRetrievalResult result) {
        String title = extractMetadataText(result.getMetadata(), "retrievableTitle");
        if (!StringUtils.hasText(title)) {
            title = extractMetadataText(result.getMetadata(), "title");
        }
        String contentPath = extractMetadataText(result.getMetadata(), "contentPath");
        return new SourceChunk(
                result.getChunkId(),
                extractMetadataText(result.getMetadata(), "sourceType"),
                extractMetadataText(result.getMetadata(), "sourceName"),
                contentPath,
                parentContentPath(contentPath),
                title,
                result.getContent()
        );
    }

    private List<SessionQueryCase> buildSessionCases(String kbId, List<SourceChunk> chunks) {
        List<SessionQueryCase> cases = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();

        for (SourceChunk chunk : chunks) {
            String seedQuery = buildSeedQuery(chunk.content());
            if (!StringUtils.hasText(seedQuery) || seedQuery.length() > MAX_QUERY_LENGTH) {
                continue;
            }

            List<RetrievedChunk> seedTopChunks = ragService.retrieve(List.of(kbId), seedQuery, ONLINE_TOP_K).stream()
                    .map(this::toRetrievedChunk)
                    .toList();
            if (!hitAt(seedTopChunks, chunk.chunkId(), chunk.contentPath(), 1)) {
                continue;
            }

            List<RetrievedChunk> statelessFollowUpTopChunks = ragService.retrieve(List.of(kbId), FOLLOW_UP_QUERY, ONLINE_TOP_K).stream()
                    .map(this::toRetrievedChunk)
                    .toList();
            if (hitAt(statelessFollowUpTopChunks, chunk.chunkId(), chunk.contentPath(), 1)) {
                continue;
            }

            int sourceCount = sourceCounts.getOrDefault(chunk.sourceName(), 0);
            if (sourceCount >= Math.max(1, CASE_LIMIT / 2)) {
                continue;
            }

            String key = "follow|" + chunk.sourceName() + "|" + chunk.contentPath();
            if (!usedKeys.add(key)) {
                continue;
            }

            cases.add(new SessionQueryCase(
                    "session-follow-up-" + usedKeys.size(),
                    "follow_up_low_info",
                    seedQuery,
                    FOLLOW_UP_QUERY,
                    chunk.sourceName(),
                    chunk.contentPath(),
                    chunk.chunkId()
            ));
            sourceCounts.put(chunk.sourceName(), sourceCount + 1);
            if (cases.size() >= Math.max(1, CASE_LIMIT / 2)) {
                break;
            }
        }

        for (int i = 0; i < chunks.size() - 1 && cases.size() < CASE_LIMIT; i++) {
            SourceChunk current = chunks.get(i);
            SourceChunk next = chunks.get(i + 1);
            if (current.contentPath().equals(next.contentPath())) {
                continue;
            }

            String seedQuery = buildSeedQuery(current.content());
            String followUpQuery = next.contentPath();
            if (!StringUtils.hasText(seedQuery) || !StringUtils.hasText(followUpQuery) || followUpQuery.length() > MAX_QUERY_LENGTH) {
                continue;
            }

            List<RetrievedChunk> seedTopChunks = ragService.retrieve(List.of(kbId), seedQuery, ONLINE_TOP_K).stream()
                    .map(this::toRetrievedChunk)
                    .toList();
            if (!hitAt(seedTopChunks, current.chunkId(), current.contentPath(), 1)) {
                continue;
            }

            cases.add(new SessionQueryCase(
                    "session-topic-switch-" + (cases.size() + 1),
                    "topic_switch_after_context",
                    seedQuery,
                    followUpQuery,
                    next.sourceName(),
                    next.contentPath(),
                    next.chunkId()
            ));
        }
        return cases;
    }

    private SessionOnlineE2eReport buildReport(List<SessionEvaluatedCase> cases) {
        List<SessionGroupSummary> groups = cases.stream()
                .collect(Collectors.groupingBy(SessionEvaluatedCase::type, LinkedHashMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .map(entry -> new SessionGroupSummary(
                        entry.getKey(),
                        entry.getValue().size(),
                        sessionHitRate(entry.getValue(), false, true),
                        sessionHitRate(entry.getValue(), false, false),
                        sessionHitRate(entry.getValue(), true, true),
                        sessionHitRate(entry.getValue(), true, false),
                        sessionHitRate(entry.getValue(), true, true) - sessionHitRate(entry.getValue(), false, true),
                        sessionHitRate(entry.getValue(), true, false) - sessionHitRate(entry.getValue(), false, false)
                ))
                .toList();

        return new SessionOnlineE2eReport(
                cases.size(),
                sessionHitRate(cases, false, true),
                sessionHitRate(cases, false, false),
                sessionHitRate(cases, true, true),
                sessionHitRate(cases, true, false),
                sessionHitRate(cases, true, true) - sessionHitRate(cases, false, true),
                sessionHitRate(cases, true, false) - sessionHitRate(cases, false, false),
                groups,
                cases
        );
    }

    private double sessionHitRate(List<SessionEvaluatedCase> cases, boolean contextual, boolean at1) {
        if (cases.isEmpty()) {
            return 0D;
        }
        long hitCount = cases.stream()
                .filter(item -> {
                    if (contextual) {
                        return at1 ? item.contextualHitAt1() : item.contextualHitAtTopK();
                    }
                    return at1 ? item.statelessHitAt1() : item.statelessHitAtTopK();
                })
                .count();
        return (double) hitCount / cases.size();
    }

    private boolean hitAt(List<RetrievedChunk> topChunks, String expectedChunkId, String expectedContentPath, int k) {
        return topChunks.stream()
                .limit(k)
                .anyMatch(chunk -> expectedChunkId.equals(chunk.chunkId())
                        || expectedContentPath.equals(chunk.contentPath()));
    }

    private RetrievedChunk toRetrievedChunk(RagRetrievalResult result) {
        return new RetrievedChunk(
                result.getChunkId(),
                extractMetadataText(result.getMetadata(), "sourceName"),
                extractMetadataText(result.getMetadata(), "contentPath"),
                extractMetadataText(result.getMetadata(), "retrievableTitle"),
                result.getRank(),
                result.getDistance()
        );
    }

    private String buildSeedQuery(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String normalized = content.replace("\r", " ")
                .replace("\n", " ")
                .replace("-", " ")
                .replace("*", " ")
                .replace("|", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String[] sentences = normalized.split("[。！？；]");
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.length() >= 12) {
                return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
            }
        }
        return normalized.length() >= 12 ? normalized.substring(0, Math.min(normalized.length(), 40)) : null;
    }

    private String createEvaluationChatSession() {
        ChatSession chatSession = ChatSession.builder()
                .userId("rag-eval-user")
                .agentId(null)
                .title("rag-session-eval")
                .metadata(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        chatSessionMapper.insert(chatSession);
        return chatSession.getId();
    }

    private String parentContentPath(String contentPath) {
        if (!StringUtils.hasText(contentPath)) {
            return "";
        }
        int separatorIndex = contentPath.lastIndexOf(" > ");
        if (separatorIndex <= 0) {
            return contentPath;
        }
        return contentPath.substring(0, separatorIndex);
    }

    private String extractMetadataText(String metadata, String fieldName) {
        if (!StringUtils.hasText(metadata)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode node = root.get(fieldName);
            return node != null && node.isTextual() ? node.asText() : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @ImportAutoConfiguration({
            JacksonAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            MybatisAutoConfiguration.class
    })
    @MapperScan("com.kama.jchatmind.mapper")
    @Import({
            ChatSessionConverter.class,
            QueryRewriteServiceImpl.class,
            RagServiceImpl.class
    })
    static class RagSessionOnlineE2eTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ChatSessionFacadeService chatSessionFacadeService(
                ChatSessionMapper chatSessionMapper,
                ChatSessionConverter chatSessionConverter
        ) {
            return new ChatSessionFacadeServiceImpl(chatSessionMapper, chatSessionConverter);
        }

        @Bean
        KnowledgeTools knowledgeTools(
                RagService ragService,
                ChatSessionFacadeService chatSessionFacadeService
        ) {
            return new KnowledgeTools(ragService, chatSessionFacadeService);
        }
    }

    private record SourceChunk(
            String chunkId,
            String sourceType,
            String sourceName,
            String contentPath,
            String parentContentPath,
            String title,
            String content
    ) {
        boolean usableForSessionEvaluation() {
            return StringUtils.hasText(chunkId)
                    && StringUtils.hasText(sourceName)
                    && StringUtils.hasText(contentPath)
                    && StringUtils.hasText(parentContentPath)
                    && StringUtils.hasText(title)
                    && StringUtils.hasText(content)
                    && contentPath.contains(" > ");
        }
    }

    private record SessionQueryCase(
            String id,
            String type,
            String seedQuery,
            String followUpQuery,
            String expectedSourceName,
            String expectedContentPath,
            String expectedChunkId
    ) {
    }

    private record RetrievedChunk(
            String chunkId,
            String sourceName,
            String contentPath,
            String retrievableTitle,
            Integer rank,
            Double distance
    ) {
    }

    private record SessionEvaluatedCase(
            String id,
            String type,
            String seedQuery,
            String followUpQuery,
            String expectedSourceName,
            String expectedContentPath,
            String expectedChunkId,
            RagRetrievalContext sessionContext,
            List<RetrievedChunk> statelessTopChunks,
            List<RetrievedChunk> contextualTopChunks,
            boolean statelessHitAt1,
            boolean statelessHitAtTopK,
            boolean contextualHitAt1,
            boolean contextualHitAtTopK
    ) {
    }

    private record SessionGroupSummary(
            String type,
            int total,
            double statelessHitAt1,
            double statelessHitAtTopK,
            double contextualHitAt1,
            double contextualHitAtTopK,
            double contextualGainAt1,
            double contextualGainAtTopK
    ) {
    }

    private record SessionOnlineE2eReport(
            int total,
            double statelessHitAt1,
            double statelessHitAtTopK,
            double contextualHitAt1,
            double contextualHitAtTopK,
            double contextualGainAt1,
            double contextualGainAtTopK,
            List<SessionGroupSummary> groups,
            List<SessionEvaluatedCase> cases
    ) {
    }
}
