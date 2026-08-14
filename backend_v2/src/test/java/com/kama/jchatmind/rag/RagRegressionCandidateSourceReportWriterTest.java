package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateSourceReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesJsonReportToTheRequestedTargetPath() throws Exception {
        RagRegressionCandidateSourceReport report = new RagRegressionCandidateSourceReport(
                "regression-v1-candidate", 1, 1, 0, "not_attempted",
                List.of(new RagRegressionCandidateSourceReport.Item(
                        "candidate-001", "interview-qa", "interview-qa#section#0", "标题", true, true, "verified"
                ))
        );
        Path output = tempDir.resolve("candidate-source-report.json");

        new RagRegressionCandidateSourceReportWriter(new ObjectMapper()).write(output, report);

        assertTrue(java.nio.file.Files.readString(output).contains("\"verified\""));
    }
}
