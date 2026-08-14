package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateSourceVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    void verifiesSourceHashAndExactHeadingAnchorWithoutReadingDatabase() throws Exception {
        Path source = tempDir.resolve("interview.md");
        Files.writeString(source, "# 面试 Q&A\n### 1. 用户认证这块，项目里到底是怎么实现的？\n内容\n");
        String sha256 = RagRegressionCandidateSourceVerifier.sha256(source);
        RagRegressionCandidateCase item = new RagRegressionCandidateCase(
                "candidate-001", "用户认证如何实现？", "user_like_question", "easy",
                "interview-qa#项目概览>用户认证#0", "项目概览 > 用户认证", "interview-qa", sha256,
                List.of(), List.of(), List.of("interview-qa#项目概览>用户认证#0"),
                List.of("认证链路"), false, null, "candidate", null, null, null, List.of("auth")
        );
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "regression-v1-candidate", "candidate", "kb-id", List.of(item)
        );

        RagRegressionCandidateSourceReport report = new RagRegressionCandidateSourceVerifier()
                .verify(dataset, Map.of("interview-qa", source), Map.of(
                        "interview-qa#项目概览>用户认证#0", new RagRegressionCandidateSourceAnchor(
                                "interview-qa", sha256, "1. 用户认证这块，项目里到底是怎么实现的？"
                        )
                ));

        assertEquals(1, report.total());
        assertEquals(1, report.verified());
        assertEquals(0, report.failed());
        assertTrue(report.items().get(0).hashMatches());
        assertTrue(report.items().get(0).anchorMatches());
        assertEquals("not_attempted", report.runtimeChunkUuidMappingStatus());
    }

    @Test
    void reportsAChangedSourceHashAndMissingAnchorSeparately() throws Exception {
        Path source = tempDir.resolve("sql.md");
        Files.writeString(source, "# SQL\n### 已有标题\n");
        RagRegressionCandidateCase item = new RagRegressionCandidateCase(
                "candidate-002", "索引是否执行？", "hard_negative", "hard",
                "sql#优化>索引#0", "优化 > 索引", "sql-tuning", "0".repeat(64),
                List.of(), List.of(), List.of("sql#优化>索引#0"),
                List.of("无法确认"), false, null, "candidate", null, null, null, List.of("sql")
        );
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "regression-v1-candidate", "candidate", "kb-id", List.of(item)
        );

        RagRegressionCandidateSourceReport report = new RagRegressionCandidateSourceVerifier()
                .verify(dataset, Map.of("sql-tuning", source), Map.of(
                        "sql#优化>索引#0", new RagRegressionCandidateSourceAnchor("sql-tuning", "0".repeat(64), "不存在的标题")
                ));

        assertEquals(0, report.verified());
        assertEquals(1, report.failed());
        assertTrue(!report.items().get(0).hashMatches());
        assertTrue(!report.items().get(0).anchorMatches());
    }

    @Test
    void verifiesEveryAdditionalGoldLogicalChunk() throws Exception {
        Path interviewSource = tempDir.resolve("interview.md");
        Path sqlSource = tempDir.resolve("sql.md");
        Files.writeString(interviewSource, "# 面试\n### 主章节\n");
        Files.writeString(sqlSource, "# SQL\n### 辅助章节\n");
        RagRegressionCandidateCase item = new RagRegressionCandidateCase(
                "candidate-003", "跨文档问题", "cross_document", "hard",
                "interview#主#0", "主", "interview-qa", RagRegressionCandidateSourceVerifier.sha256(interviewSource),
                List.of(), List.of("sql#辅助#0"), List.of("interview#主#0", "sql#辅助#0"),
                List.of("需要两份资料"), false, null, "candidate", null, null, null, List.of("cross-document")
        );
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "regression-v1-candidate", "candidate", "kb-id", List.of(item)
        );

        RagRegressionCandidateSourceReport report = new RagRegressionCandidateSourceVerifier().verify(
                dataset,
                Map.of("interview-qa", interviewSource, "sql-tuning", sqlSource),
                Map.of(
                        "interview#主#0", new RagRegressionCandidateSourceAnchor(
                                "interview-qa", RagRegressionCandidateSourceVerifier.sha256(interviewSource), "主章节"
                        ),
                        "sql#辅助#0", new RagRegressionCandidateSourceAnchor(
                                "sql-tuning", RagRegressionCandidateSourceVerifier.sha256(sqlSource), "辅助章节"
                        )
                )
        );

        assertEquals(2, report.total());
        assertEquals(2, report.verified());
        assertTrue(report.items().stream().anyMatch(entry -> "sql#辅助#0".equals(entry.logicalChunkId())));
    }
}
