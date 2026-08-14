package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateRuntimeEvaluationRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluatesCandidateRuntimeReplayFilesAndWritesReport() throws Exception {
        Path directory = Files.createTempDirectory("rag-runtime-eval");
        Path mappingPath = directory.resolve("mapping.json");
        Path replayPath = directory.resolve("replay.jsonl");
        Path reportPath = directory.resolve("report.json");
        RagRegressionCandidateDataset dataset = dataset();
        RagRegressionCandidateChunkUuidMapping mapping = mapping();

        objectMapper.writeValue(mappingPath.toFile(), mapping);
        Files.writeString(replayPath, objectMapper.writeValueAsString(
                new RagRegressionCandidateRuntimeReplay("answerable", List.of("uuid-1"), false)
        ) + System.lineSeparator() + objectMapper.writeValueAsString(
                new RagRegressionCandidateRuntimeReplay("abstain", List.of(), true)
        ));

        RagRegressionCandidateRuntimeEvaluationReport report = new RagRegressionCandidateRuntimeEvaluationRunner().evaluateAndWrite(
                dataset, mappingPath, replayPath, reportPath
        );

        assertEquals(2, report.evaluatedCases());
        assertEquals(1.0, report.recallAt5(), 0.0001);
        assertTrue(Files.readString(reportPath).contains("\"runtime_replay\""));
    }

    @Test
    void rejectsReplayFilesThatMissOrAddRuntimeCases() throws Exception {
        Path directory = Files.createTempDirectory("rag-runtime-eval-invalid");
        Path mappingPath = directory.resolve("mapping.json");
        Path replayPath = directory.resolve("replay.jsonl");
        RagRegressionCandidateDataset dataset = dataset();

        objectMapper.writeValue(mappingPath.toFile(), mapping());
        Files.writeString(replayPath, objectMapper.writeValueAsString(
                new RagRegressionCandidateRuntimeReplay("answerable", List.of("uuid-1"), false)
        ) + System.lineSeparator() + objectMapper.writeValueAsString(
                new RagRegressionCandidateRuntimeReplay("agent", List.of(), false)
        ));

        assertThrows(IllegalStateException.class, () -> new RagRegressionCandidateRuntimeEvaluationRunner().evaluateAndWrite(
                dataset, mappingPath, replayPath, directory.resolve("report.json")
        ));
    }

    private RagRegressionCandidateDataset dataset() {
        return new RagRegressionCandidateDataset("candidate", "candidate", "kb", List.of(
                new RagRegressionCandidateCase(
                        "answerable", "问题", "user_like_question", "easy", "interview-qa#章节#0", "章节",
                        "interview-qa", "a".repeat(64), List.of(), List.of(), List.of("interview-qa#章节#0"),
                        List.of("事实"), false, null, "approved", "manual", "reviewer", "2026-08-13T12:00:00+08:00", List.of()
                ),
                new RagRegressionCandidateCase(
                        "abstain", "无证据", "no_answer", "hard", "interview-qa#拒答#0", "章节",
                        "interview-qa", "a".repeat(64), List.of(), List.of(), List.of(),
                        List.of(), true, "missing_evidence", "approved", "manual", "reviewer", "2026-08-13T12:00:00+08:00", List.of()
                ),
                new RagRegressionCandidateCase(
                        "agent", "受控候选", "user_like_question", "easy", "agent#章节#0", "章节",
                        "agent-harness-candidate", "a".repeat(64), List.of(), List.of(), List.of("agent#章节#0"),
                        List.of("事实"), false, null, "approved", "manual", "reviewer", "2026-08-13T12:00:00+08:00", List.of()
                )
        ));
    }

    private RagRegressionCandidateChunkUuidMapping mapping() {
        return RagRegressionCandidateChunkUuidMapping.fromItems("read_only", "kb", List.of(
                new RagRegressionCandidateChunkUuidMapping.Item(
                        "interview-qa#章节#0", "interview-qa", "章节", List.of("uuid-1"), "mapped"
                )
        ));
    }
}
