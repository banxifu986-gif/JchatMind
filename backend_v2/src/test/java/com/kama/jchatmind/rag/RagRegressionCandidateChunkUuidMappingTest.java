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

@SpringBootTest(classes = RagRegressionCandidateChunkUuidMappingTest.MappingTestConfig.class)
@ActiveProfiles("rag-eval")
@EnabledIfSystemProperty(named = "rag.eval.uuid-mapping.enabled", matches = "true")
@EnabledIfSystemProperty(named = "rag.eval.uuid-mapping.kb-id", matches = ".+")
class RagRegressionCandidateChunkUuidMappingTest {
    private static final String KB_ID_PROPERTY = "rag.eval.uuid-mapping.kb-id";

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void mapsRuntimeChunkUuidsOnlyWhenExplicitlyEnabledForOneKnowledgeBase() throws Exception {
        String knowledgeBaseId = System.getProperty(KB_ID_PROPERTY);
        RagRegressionCandidateDataset dataset = RagRegressionCandidateDatasetLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate.json"
        );
        Map<String, RagRegressionCandidateSourceAnchor> anchors = RagRegressionCandidateSourceAnchorLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate-anchors.json", dataset
        );
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
