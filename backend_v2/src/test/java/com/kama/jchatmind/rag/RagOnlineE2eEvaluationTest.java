package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.RagService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SpringBootTest(classes = RagOnlineE2eEvaluationTest.RagOnlineE2eTestConfig.class)
@ActiveProfiles("rag-eval")
class RagOnlineE2eEvaluationTest {

    private static final int ONLINE_TOP_K = 3;
    private static final int CASE_LIMIT_PER_TYPE = 4;
    private static final int MAX_QUERY_LENGTH = 80;

    @Autowired
    private RagService ragService;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${rag.eval.real-kb-id:}")
    private String realKbId;

    private Path reportOutputPath;

    @BeforeEach
    void setUp() throws IOException {
        reportOutputPath = Path.of("target", "rag-eval", "online-e2e-report.json");
        Files.createDirectories(reportOutputPath.getParent());
    }

    @Test
    void evaluateOnlineRagRetrieval() throws Exception {
        Assumptions.assumeTrue(StringUtils.hasText(realKbId), "缺少 rag.eval.real-kb-id，跳过线上 RAG E2E 评测");

        List<SourceChunk> chunks = loadSourceChunks(realKbId);
        List<OnlineQueryCase> cases = buildOnlineCases(chunks);
        Assumptions.assumeTrue(!cases.isEmpty(), "没有可用 chunk，跳过线上 RAG E2E 评测");

        List<OnlineEvaluatedCase> evaluatedCases = new ArrayList<>();
        for (OnlineQueryCase queryCase : cases) {
            List<RagRetrievalResult> results = ragService.retrieve(List.of(realKbId), queryCase.query(), ONLINE_TOP_K);
            List<RetrievedChunk> topChunks = results.stream()
                    .map(this::toRetrievedChunk)
                    .toList();
            evaluatedCases.add(new OnlineEvaluatedCase(
                    queryCase.id(),
                    queryCase.type(),
                    queryCase.query(),
                    queryCase.expectedSourceName(),
                    queryCase.expectedContentPath(),
                    queryCase.expectedChunkId(),
                    topChunks,
                    hitAt(topChunks, queryCase, 1),
                    hitAt(topChunks, queryCase, ONLINE_TOP_K)
            ));
        }

        OnlineE2eReport report = buildReport(evaluatedCases);
        String jsonReport = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.writeString(reportOutputPath, jsonReport);
        System.out.println(jsonReport);
    }

    private List<SourceChunk> loadSourceChunks(String kbId) {
        return chunkBgeM3Mapper.selectTitlePathCandidatesByKbIds(List.of(kbId)).stream()
                .map(this::toSourceChunk)
                .filter(SourceChunk::usable)
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
                extractMetadataText(result.getMetadata(), "sourceName"),
                contentPath,
                parentContentPath(contentPath),
                title
        );
    }

    private List<OnlineQueryCase> buildOnlineCases(List<SourceChunk> chunks) {
        List<OnlineQueryCase> cases = new ArrayList<>();
        addCases(cases, chunks, "path_aware", chunk -> chunk.parentContentPath() + " > " + chunk.title());
        addCases(cases, chunks, "source_path", chunk -> "在 " + chunk.sourceName() + " 里，" + chunk.parentContentPath() + " > " + chunk.title() + " 这部分主要讲什么");
        addCases(cases, chunks, "title_question", this::buildUserLikeQuestion);
        return cases;
    }

    private String buildUserLikeQuestion(SourceChunk chunk) {
        String title = normalize(chunk.title());
        String focus = buildQuestionFocus(chunk);
        if (!StringUtils.hasText(focus)) {
            return null;
        }
        if (!StringUtils.hasText(title)) {
            return null;
        }
        if (title.contains("区别") || title.contains("对比")) {
            String contrastTarget = buildContrastTarget(chunk);
            return contrastTarget == null
                    ? focus + "有什么区别"
                    : focus + "和" + contrastTarget + "有什么区别";
        }
        if (title.contains("原理")) {
            return focus + "的原理是什么";
        }
        if (title.contains("为什么")) {
            return "为什么" + focus + "会这样设计";
        }
        if (title.contains("优缺点")) {
            return focus + "有什么优缺点";
        }
        if (title.contains("方案")) {
            return focus + "适合什么场景";
        }
        if (title.contains("如何") || title.contains("怎么") || title.contains("流程")
                || isGenericQaLeafTitle(title)) {
            return focus + "如何使用";
        }
        if (title.contains("总结")) {
            return focus + "主要讲了什么";
        }
        return focus + "是什么";
    }

    private String buildContrastTarget(SourceChunk chunk) {
        String parentContentPath = normalize(chunk.parentContentPath());
        if (!StringUtils.hasText(parentContentPath)) {
            return null;
        }
        int separatorIndex = parentContentPath.lastIndexOf(" > ");
        if (separatorIndex < 0) {
            return null;
        }
        String siblingOrParent = parentContentPath.substring(separatorIndex + 3).trim();
        return StringUtils.hasText(siblingOrParent) && !siblingOrParent.equals(normalize(chunk.title()))
                ? siblingOrParent
                : null;
    }

    private String buildQuestionFocus(SourceChunk chunk) {
        String title = normalize(chunk.title());
        String parentContentPath = normalize(chunk.parentContentPath());
        if (isGenericQaLeafTitle(title) && StringUtils.hasText(parentContentPath)) {
            return lastPathSegment(parentContentPath);
        }
        if (StringUtils.hasText(title)) {
            return title;
        }
        if (StringUtils.hasText(parentContentPath)) {
            return lastPathSegment(parentContentPath);
        }
        return null;
    }

    private boolean isGenericQaLeafTitle(String title) {
        return "回答".equals(title)
                || "原理".equals(title)
                || "总结".equals(title)
                || "方案".equals(title);
    }

    private void addCases(
            List<OnlineQueryCase> cases,
            List<SourceChunk> chunks,
            String type,
            QueryBuilder queryBuilder
    ) {
        Set<String> usedKeys = new LinkedHashSet<>();
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        for (SourceChunk chunk : chunks) {
            String query = queryBuilder.build(chunk);
            if (!StringUtils.hasText(query) || query.length() > MAX_QUERY_LENGTH) {
                continue;
            }
            int sourceCount = sourceCounts.getOrDefault(chunk.sourceName(), 0);
            if (sourceCount >= Math.max(1, CASE_LIMIT_PER_TYPE / 2)) {
                continue;
            }
            String key = type + "|" + chunk.sourceName() + "|" + chunk.contentPath();
            if (!usedKeys.add(key)) {
                continue;
            }
            cases.add(new OnlineQueryCase(
                    type + "-" + (usedKeys.size()),
                    type,
                    query,
                    chunk.sourceName(),
                    chunk.contentPath(),
                    chunk.chunkId()
            ));
            sourceCounts.put(chunk.sourceName(), sourceCount + 1);
            if (usedKeys.size() >= CASE_LIMIT_PER_TYPE) {
                return;
            }
        }
    }

    private OnlineE2eReport buildReport(List<OnlineEvaluatedCase> cases) {
        List<OnlineGroupSummary> groups = cases.stream()
                .collect(Collectors.groupingBy(OnlineEvaluatedCase::type, LinkedHashMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .map(entry -> toGroupSummary(entry.getKey(), entry.getValue()))
                .toList();
        return new OnlineE2eReport(
                cases.size(),
                hitRate(cases, true),
                hitRate(cases, false),
                groups,
                cases
        );
    }

    private OnlineGroupSummary toGroupSummary(String type, List<OnlineEvaluatedCase> cases) {
        return new OnlineGroupSummary(
                type,
                cases.size(),
                hitRate(cases, true),
                hitRate(cases, false)
        );
    }

    private boolean hitAt(List<RetrievedChunk> topChunks, OnlineQueryCase queryCase, int k) {
        return topChunks.stream()
                .limit(k)
                .anyMatch(chunk -> queryCase.expectedChunkId().equals(chunk.chunkId())
                        || queryCase.expectedContentPath().equals(chunk.contentPath()));
    }

    private double hitRate(List<OnlineEvaluatedCase> cases, boolean at1) {
        if (cases.isEmpty()) {
            return 0D;
        }
        long hitCount = cases.stream()
                .filter(item -> at1 ? item.hitAt1() : item.hitAtTopK())
                .count();
        return (double) hitCount / cases.size();
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

    private String lastPathSegment(String contentPath) {
        if (!StringUtils.hasText(contentPath)) {
            return null;
        }
        int separatorIndex = contentPath.lastIndexOf(" > ");
        if (separatorIndex < 0) {
            return contentPath;
        }
        return contentPath.substring(separatorIndex + 3).trim();
    }

    private String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replace("\r", "")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
            QueryRewriteServiceImpl.class,
            RagServiceImpl.class
    })
    static class RagOnlineE2eTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private interface QueryBuilder {
        String build(SourceChunk chunk);
    }

    private record SourceChunk(
            String chunkId,
            String sourceName,
            String contentPath,
            String parentContentPath,
            String title
    ) {
        boolean usable() {
            return StringUtils.hasText(chunkId)
                    && StringUtils.hasText(sourceName)
                    && StringUtils.hasText(contentPath)
                    && StringUtils.hasText(parentContentPath)
                    && StringUtils.hasText(title)
                    && contentPath.contains(" > ")
                    && title.length() >= 2
                    && title.length() <= 30;
        }
    }

    private record OnlineQueryCase(
            String id,
            String type,
            String query,
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

    private record OnlineEvaluatedCase(
            String id,
            String type,
            String query,
            String expectedSourceName,
            String expectedContentPath,
            String expectedChunkId,
            List<RetrievedChunk> topChunks,
            boolean hitAt1,
            boolean hitAtTopK
    ) {
    }

    private record OnlineGroupSummary(
            String type,
            int total,
            double hitAt1,
            double hitAtTopK
    ) {
    }

    private record OnlineE2eReport(
            int total,
            double hitAt1,
            double hitAtTopK,
            List<OnlineGroupSummary> groups,
            List<OnlineEvaluatedCase> cases
    ) {
    }
}
