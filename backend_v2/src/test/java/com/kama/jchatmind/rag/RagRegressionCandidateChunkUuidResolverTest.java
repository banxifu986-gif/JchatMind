package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateChunkUuidResolverTest {

    @Test
    void mapsOnlyAnExactSourceAndHeadingMatchToOneRuntimeUuid() {
        RagRegressionCandidateChunkUuidResolver resolver = new RagRegressionCandidateChunkUuidResolver();
        RagRegressionCandidateSourceAnchor anchor = new RagRegressionCandidateSourceAnchor(
                "interview-qa", "a".repeat(64), "用户认证如何实现"
        );

        List<RagRegressionCandidateChunkUuidMapping.Item> items = resolver.resolve(
                Map.of("interview-qa#认证#0", anchor),
                Map.of("interview-qa", "面试 Q&A"),
                List.of(
                        result("uuid-1", "面试 Q&A", "用户认证如何实现"),
                        result("uuid-2", "SQL 调优", "用户认证如何实现"),
                        result("uuid-3", "面试 Q&A", "其他标题")
                )
        );

        assertEquals(1, items.size());
        assertEquals("mapped", items.get(0).status());
        assertEquals(List.of("uuid-1"), items.get(0).runtimeChunkUuids());
    }

    @Test
    void keepsNoMatchAndAmbiguousMatchesVisibleInsteadOfSelectingOne() {
        RagRegressionCandidateChunkUuidResolver resolver = new RagRegressionCandidateChunkUuidResolver();
        Map<String, RagRegressionCandidateSourceAnchor> anchors = Map.of(
                "interview-qa#认证#0", new RagRegressionCandidateSourceAnchor("interview-qa", "a".repeat(64), "认证"),
                "sql-tuning#索引#0", new RagRegressionCandidateSourceAnchor("sql-tuning", "b".repeat(64), "索引")
        );

        List<RagRegressionCandidateChunkUuidMapping.Item> items = resolver.resolve(
                anchors,
                Map.of("interview-qa", "面试 Q&A", "sql-tuning", "SQL 调优"),
                List.of(result("uuid-1", "面试 Q&A", "认证"), result("uuid-2", "面试 Q&A", "认证"))
        );

        assertEquals("ambiguous", item(items, "interview-qa#认证#0").status());
        assertEquals(List.of("uuid-1", "uuid-2"), item(items, "interview-qa#认证#0").runtimeChunkUuids());
        assertEquals("unmapped", item(items, "sql-tuning#索引#0").status());
    }

    @Test
    void excludesCandidateSourcesWithoutAnAuthorizedRuntimeKnowledgeBaseMapping() {
        RagRegressionCandidateChunkUuidResolver resolver = new RagRegressionCandidateChunkUuidResolver();

        List<RagRegressionCandidateChunkUuidMapping.Item> items = resolver.resolve(
                Map.of(
                        "agent-harness#审批#0",
                        new RagRegressionCandidateSourceAnchor("agent-harness-candidate", "a".repeat(64), "人工审批")
                ),
                Map.of("interview-qa", "面试 Q&A"),
                List.of()
        );

        assertTrue(items.isEmpty());
    }

    @Test
    void summarizesMappedUnmappedAndAmbiguousItemsWithoutHidingFailures() {
        RagRegressionCandidateChunkUuidMapping mapping = RagRegressionCandidateChunkUuidMapping.fromItems(
                "read_only",
                "kb",
                List.of(
                        new RagRegressionCandidateChunkUuidMapping.Item("a", "doc", "A", List.of("uuid-a"), "mapped"),
                        new RagRegressionCandidateChunkUuidMapping.Item("b", "doc", "B", List.of(), "unmapped"),
                        new RagRegressionCandidateChunkUuidMapping.Item("c", "doc", "C", List.of("uuid-c1", "uuid-c2"), "ambiguous")
                )
        );

        assertEquals("read_only", mapping.executionStatus());
        assertEquals(3, mapping.total());
        assertEquals(1, mapping.mapped());
        assertEquals(1, mapping.unmapped());
        assertEquals(1, mapping.ambiguous());
    }

    private RagRegressionCandidateChunkUuidMapping.Item item(
            List<RagRegressionCandidateChunkUuidMapping.Item> items,
            String logicalChunkId
    ) {
        return items.stream().filter(item -> logicalChunkId.equals(item.logicalChunkId())).findFirst().orElseThrow();
    }

    private RagRetrievalResult result(String chunkId, String sourceName, String title) {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId(chunkId);
        result.setMetadata("{\"sourceName\":\"" + sourceName + "\",\"retrievableTitle\":\"" + title + "\"}");
        return result;
    }
}
