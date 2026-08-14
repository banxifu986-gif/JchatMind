package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagRegressionCandidateSourceVerificationTest {

    private static final String SOURCE_ROOT_PROPERTY = "rag.eval.candidate-source-root";

    @Test
    void verifiesAuthorizedCandidateSourcesAndWritesAnEphemeralReport() throws Exception {
        String sourceRootValue = System.getProperty(SOURCE_ROOT_PROPERTY);
        Assumptions.assumeTrue(sourceRootValue != null && !sourceRootValue.isBlank(),
                "未显式设置 -D" + SOURCE_ROOT_PROPERTY + "，跳过真实候选来源核验");
        Path sourceRoot = Path.of(sourceRootValue);
        Path interviewSource = findSingleMarkdown(sourceRoot.resolve("271c2c74-bd0e-4a52-a87e-0b7f0e98c0ae"));
        Path sqlSource = findSingleMarkdown(sourceRoot.resolve("2a070c14-86bf-462c-bbe4-c94aa5f03a3a"));
        Path agentCandidateSource = Path.of("src", "test", "resources", "rag-eval", "datasets", "corpus",
                "agent-harness-candidate-v1", "agent-execution-and-memory-boundaries.md");
        RagRegressionCandidateDataset dataset = RagRegressionCandidateDatasetLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate.json"
        );
        Map<String, RagRegressionCandidateSourceAnchor> anchors = RagRegressionCandidateSourceAnchorLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate-anchors.json", dataset
        );

        RagRegressionCandidateSourceReport report = new RagRegressionCandidateSourceVerifier().verify(
                dataset, Map.of(
                        "interview-qa", interviewSource,
                        "sql-tuning", sqlSource,
                        "agent-harness-candidate", agentCandidateSource
                ), anchors
        );
        Path output = Path.of("target", "rag-eval", "candidates", "regression-v1-candidate-source-report.json");
        new RagRegressionCandidateSourceReportWriter(new ObjectMapper()).write(output, report);

        assertEquals(59, report.total());
        assertEquals(59, report.verified());
        assertEquals(0, report.failed());
    }

    private Path findSingleMarkdown(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("未找到候选来源 Markdown: " + directory));
        }
    }
}
