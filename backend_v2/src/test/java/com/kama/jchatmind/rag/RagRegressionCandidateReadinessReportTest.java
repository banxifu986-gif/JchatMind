package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateReadinessReportTest {

    @Test
    void writesCurrentCandidateReadinessSnapshot() throws Exception {
        RagRegressionCandidateDataset dataset = RagRegressionCandidateDatasetLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate.json"
        );
        RagRegressionCandidateReadinessReport report = new RagRegressionCandidateReadinessEvaluator().evaluate(dataset);
        Path output = Path.of("target", "rag-eval", "candidates", "regression-v1-candidate-readiness-report.json");

        new RagRegressionCandidateSourceReportWriter(new ObjectMapper()).write(output, report);

        String reportJson = Files.readString(output);
        assertTrue(!reportJson.contains("\"insufficient_approved_case_count\""));
        assertTrue(!reportJson.contains("\"pending_human_review\""));
        assertTrue(reportJson.contains("\"eligibleCases\" : 57"));
        assertTrue(reportJson.contains("\"runtimeEligibleCases\" : 40"));
        assertTrue(reportJson.contains("\"runtimeUniqueRetrievalGoldChunks\" : 38"));
        assertTrue(reportJson.contains("\"approvedCases\" : 57"));
        assertTrue(!reportJson.contains("\"insufficient_runtime_eligible_case_count\""));
        assertTrue(reportJson.contains("\"rejectedCases\" : 1"));
    }
}
