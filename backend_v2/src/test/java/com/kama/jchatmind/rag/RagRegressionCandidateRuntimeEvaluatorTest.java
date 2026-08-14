package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateRuntimeEvaluatorTest {

    @Test
    void evaluatesOnlyApprovedMappedCasesAndKeepsAbstentionSeparate() {
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "candidate", "candidate", "kb", List.of(
                        answerable("approved", "approved", "logical#0"),
                        answerable("rejected", "rejected", "rejected#0"),
                        abstention("abstain"),
                        agentCandidate("agent-candidate")
                )
        );
        RagRegressionCandidateChunkUuidMapping mapping = mapping(List.of(
                item("logical#0", List.of("uuid-1"), "mapped")
        ));

        RagRegressionCandidateRuntimeEvaluationReport report = new RagRegressionCandidateRuntimeEvaluator().evaluate(
                dataset,
                mapping,
                Map.of(
                        "approved", new RagRegressionCandidateRuntimeReplay("approved", List.of("uuid-1"), false),
                        "abstain", new RagRegressionCandidateRuntimeReplay("abstain", List.of("uuid-noise"), true)
                )
        );

        assertEquals(2, report.evaluatedCases());
        assertEquals(1, report.answerableCases());
        assertEquals(1, report.abstentionCases());
        assertEquals(1.0, report.recallAt5(), 0.0001);
        assertEquals(1.0, report.mrrAt3(), 0.0001);
        assertEquals(1.0, report.contextPrecisionAt5(), 0.0001);
        assertEquals(1.0, report.contextRecallAt5(), 0.0001);
        assertEquals(1.0, report.abstentionAccuracy(), 0.0001);
    }

    @Test
    void returnsZeroAbstentionAccuracyWhenTheRuntimeSubsetHasNoAbstentionCases() {
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "candidate", "candidate", "kb", List.of(answerable("approved", "approved", "logical#0"))
        );
        RagRegressionCandidateChunkUuidMapping mapping = mapping(List.of(
                item("logical#0", List.of("uuid-1"), "mapped")
        ));

        RagRegressionCandidateRuntimeEvaluationReport report = new RagRegressionCandidateRuntimeEvaluator().evaluate(
                dataset, mapping, Map.of("approved", new RagRegressionCandidateRuntimeReplay("approved", List.of("uuid-1"), false))
        );

        assertEquals(0.0, report.abstentionAccuracy(), 0.0001);
    }

    @Test
    void refusesToEvaluateAnApprovedAnswerableCaseWithoutOneToOneRuntimeMapping() {
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "candidate", "candidate", "kb", List.of(answerable("approved", "approved", "logical#0"))
        );
        RagRegressionCandidateChunkUuidMapping mapping = mapping(List.of(
                item("logical#0", List.of(), "unmapped")
        ));

        assertThrows(IllegalStateException.class, () -> new RagRegressionCandidateRuntimeEvaluator().evaluate(
                dataset, mapping, Map.of("approved", new RagRegressionCandidateRuntimeReplay("approved", List.of(), false))
        ));
    }

    @Test
    void refusesMappingReportsThatWereNotCreatedByTheReadOnlyMappingFlow() {
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "candidate", "candidate", "kb", List.of(answerable("approved", "approved", "logical#0"))
        );
        RagRegressionCandidateChunkUuidMapping mapping = new RagRegressionCandidateChunkUuidMapping(
                "not_attempted", "kb", 0, 0, 0, 0, List.of()
        );

        assertThrows(IllegalStateException.class, () -> new RagRegressionCandidateRuntimeEvaluator().evaluate(
                dataset, mapping, Map.of("approved", new RagRegressionCandidateRuntimeReplay("approved", List.of(), false))
        ));
    }

    @Test
    void refusesReadOnlyMappingReportsForAnotherKnowledgeBase() {
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "candidate", "candidate", "kb-a", List.of(answerable("approved", "approved", "logical#0"))
        );
        RagRegressionCandidateChunkUuidMapping mapping = mapping(List.of(item("logical#0", List.of("uuid-1"), "mapped")));

        assertThrows(IllegalStateException.class, () -> new RagRegressionCandidateRuntimeEvaluator().evaluate(
                dataset, mapping, Map.of("approved", new RagRegressionCandidateRuntimeReplay("approved", List.of("uuid-1"), false))
        ));
    }

    @Test
    void writesRuntimeReplayReportForLaterComparison() throws Exception {
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "candidate", "candidate", "kb", List.of(answerable("approved", "approved", "logical#0"))
        );
        Path output = Path.of("target", "rag-eval", "candidates", "runtime-replay-test-report.json");

        RagRegressionCandidateRuntimeEvaluationReport report = new RagRegressionCandidateRuntimeEvaluator().evaluateAndWrite(
                dataset,
                mapping(List.of(item("logical#0", List.of("uuid-1"), "mapped"))),
                Map.of("approved", new RagRegressionCandidateRuntimeReplay("approved", List.of("uuid-1"), false)),
                output
        );

        assertEquals("runtime_replay", report.executionMode());
        assertTrue(Files.readString(output).contains("\"recallAt5\""));
    }

    private RagRegressionCandidateCase answerable(String caseId, String reviewStatus, String logicalChunkId) {
        return new RagRegressionCandidateCase(
                caseId, "问题", "user_like_question", "easy", logicalChunkId, "章节", "interview-qa", "a".repeat(64),
                List.of(), List.of(), List.of(logicalChunkId), List.of("事实"), false, null,
                reviewStatus, "manual", "reviewer", "2026-08-13T12:00:00+08:00", List.of("test")
        );
    }

    private RagRegressionCandidateCase abstention(String caseId) {
        return new RagRegressionCandidateCase(
                caseId, "没有证据的问题", "no_answer", "hard", "interview-qa#anchor#0", "章节", "interview-qa", "a".repeat(64),
                List.of(), List.of(), List.of(), List.of(), true, "missing_evidence",
                "approved", "manual", "reviewer", "2026-08-13T12:00:00+08:00", List.of("abstention")
        );
    }

    private RagRegressionCandidateCase agentCandidate(String caseId) {
        return new RagRegressionCandidateCase(
                caseId, "受控候选问题", "user_like_question", "easy", "agent#章节#0", "章节",
                "agent-harness-candidate", "a".repeat(64), List.of(), List.of(), List.of("agent#章节#0"), List.of("事实"),
                false, null, "approved", "manual", "reviewer", "2026-08-13T12:00:00+08:00", List.of("synthetic")
        );
    }

    private RagRegressionCandidateChunkUuidMapping mapping(List<RagRegressionCandidateChunkUuidMapping.Item> items) {
        return RagRegressionCandidateChunkUuidMapping.fromItems("read_only", "kb", items);
    }

    private RagRegressionCandidateChunkUuidMapping.Item item(String logicalChunkId, List<String> uuids, String status) {
        return new RagRegressionCandidateChunkUuidMapping.Item(logicalChunkId, "doc", "章节", uuids, status);
    }
}
