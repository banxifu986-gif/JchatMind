package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(classes = RagRegressionCandidateChunkUuidMappingTest.MappingTestConfig.class)
@ActiveProfiles("rag-eval")
@EnabledIfSystemProperty(named = "rag.eval.uuid-mapping.enabled", matches = "true")
@EnabledIfSystemProperty(named = "rag.eval.uuid-mapping.kb-id", matches = ".+")
@EnabledIfSystemProperty(named = "rag.eval.candidate-source-root", matches = ".+")
class RagRegressionCandidateChunkUuidMappingTest {
    private static final String KB_ID_PROPERTY = "rag.eval.uuid-mapping.kb-id";
    private static final String SOURCE_ROOT_PROPERTY = "rag.eval.candidate-source-root";

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void mapsRuntimeChunkUuidsOnlyWhenExplicitlyEnabledForOneKnowledgeBase() throws Exception {
        String knowledgeBaseId = System.getProperty(KB_ID_PROPERTY);
        Path sourceRoot = Path.of(System.getProperty(SOURCE_ROOT_PROPERTY));
        RagRegressionCandidateDataset dataset = RagRegressionCandidateDatasetLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate.json"
        );
        Map<String, RagRegressionCandidateSourceAnchor> anchors = RagRegressionCandidateSourceAnchorLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate-anchors.json", dataset
        );
        RagRegressionCandidateSourceReport sourceReport = new RagRegressionCandidateSourceVerifier().verify(
                dataset, Map.of(
                        "interview-qa", findSingleMarkdown(sourceRoot.resolve("271c2c74-bd0e-4a52-a87e-0b7f0e98c0ae")),
                        "sql-tuning", findSingleMarkdown(sourceRoot.resolve("2a070c14-86bf-462c-bbe4-c94aa5f03a3a")),
                        "agent-harness-candidate", Path.of("src", "test", "resources", "rag-eval", "datasets", "corpus",
                                "agent-harness-candidate-v1", "agent-execution-and-memory-boundaries.md")
                ), anchors
        );
        assertEquals(59, sourceReport.verified());
        assertEquals(0, sourceReport.failed());
        List<RagRetrievalResult> candidates = chunkBgeM3Mapper.selectTitlePathCandidatesByKbIds(List.of(knowledgeBaseId));
        RagRegressionCandidateChunkUuidMapping mapping = RagRegressionCandidateChunkUuidMapping.fromItems(
                "read_only",
                knowledgeBaseId,
                new RagRegressionCandidateChunkUuidResolver().resolve(
                        anchors,
                        Map.of("interview-qa", "面试 Q&A", "sql-tuning", "SQL调优与SQL八股梳理"),
                        candidates
                )
        );
        Path output = Path.of("target", "rag-eval", "candidates", "regression-v1-candidate-chunk-uuid-mapping.json");
        new RagRegressionCandidateSourceReportWriter(objectMapper).write(output, mapping);

        assertEquals(knowledgeBaseId, mapping.knowledgeBaseId());
        assertEquals(
                anchors.values().stream()
                        .filter(anchor -> List.of("interview-qa", "sql-tuning").contains(anchor.sourceDocumentLogicalId()))
                        .count(),
                mapping.total()
        );
        RagRegressionCandidateReadinessReport readiness = new RagRegressionCandidateReadinessEvaluator().evaluate(
                dataset, new RagRegressionCandidateReadinessEvaluator.Thresholds(40, 1, 1, true), mapping
        );
        assertFalse(readiness.freezeBlockers().contains("runtime_uuid_mapping_not_completed"));
    }

    private Path findSingleMarkdown(Path directory) throws Exception {
        try (var paths = java.nio.file.Files.walk(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("未找到候选来源 Markdown: " + directory));
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
    static class MappingTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
