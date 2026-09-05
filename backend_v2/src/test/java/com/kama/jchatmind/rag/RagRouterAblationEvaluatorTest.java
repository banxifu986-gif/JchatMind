package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRouterAblationEvaluatorTest {

    @Test
    void comparesRouterWithFixedChainAndKeepsDefaultSwitchOutOfTheGate() throws Exception {
        RagRouterAblationDataset dataset = dataset();
        RagRouterAblationEvaluationReport report = new RagRouterAblationEvaluator().evaluateAndWrite(
                dataset,
                List.of(
                        replay("answerable", List.of("noise", "gold"), List.of("gold", "noise"),
                                "PRIVATE_RAG", "PRIVATE_RAG", false, false, false, false, 100, 110, 100, 105),
                        replay("no-answer", List.of("noise"), List.of(),
                                "PRIVATE_RAG", "ABSTAIN", false, true, false, false, 90, 80, 100, 95),
                        replay("external-denied", List.of("private"), List.of(),
                                "PRIVATE_RAG", "ABSTAIN", false, true, false, false, 90, 80, 100, 95)
                ),
                Path.of("target", "rag-eval", "router", "regression-v1-router-ablation.json")
        );

        assertEquals("regression-v1", report.datasetVersion());
        assertEquals("eligible_for_router_ab", report.gate().verdict());
        assertTrue(report.gate().reasons().isEmpty());
        assertEquals(0, report.router().permissionViolations());
        assertTrue(Files.readString(Path.of(
                "target", "rag-eval", "router", "regression-v1-router-ablation.json"
        )).contains("\"datasetVersion\""));
    }

    @Test
    void blocksRouterCandidateWhenItsP95OrTokenCostExceedsTheFixedChainBudget() {
        RagRouterAblationDataset dataset = new RagRouterAblationDataset("regression-v1", List.of(
                new RagRouterAblationCase("answerable", Set.of("gold"), false, true, "PRIVATE_RAG")
        ));
        RagRouterAblationEvaluationReport report = new RagRouterAblationEvaluator().evaluate(
                dataset,
                List.of(replay("answerable", List.of("gold"), List.of("gold"),
                        "PRIVATE_RAG", "PRIVATE_RAG", false, false, false, false, 100, 116, 100, 111))
        );

        assertEquals("inconclusive", report.gate().verdict());
        assertTrue(report.gate().reasons().contains("router_p95_latency_above_15_percent"));
        assertTrue(report.gate().reasons().contains("router_token_cost_above_10_percent"));
    }

    @Test
    void refusesAReplayWithAnUnauthorizedExternalCall() {
        RagRouterAblationDataset dataset = dataset();

        assertThrows(IllegalStateException.class, () -> new RagRouterAblationEvaluator().evaluate(
                dataset,
                List.of(replay("external-denied", List.of(), List.of(),
                        "PRIVATE_RAG", "EXTERNAL_TOOL", false, true, false, true, 100, 100, 100, 100))
        ));
    }

    private RagRouterAblationDataset dataset() {
        return new RagRouterAblationDataset("regression-v1", List.of(
                new RagRouterAblationCase("answerable", Set.of("gold"), false, true, "PRIVATE_RAG"),
                new RagRouterAblationCase("no-answer", Set.of(), true, true, "ABSTAIN"),
                new RagRouterAblationCase("external-denied", Set.of(), true, false, "ABSTAIN")
        ));
    }

    private RagRouterAblationReplay replay(
            String caseId,
            List<String> fixedRankedChunkIds,
            List<String> routerRankedChunkIds,
            String fixedRoute,
            String routerRoute,
            boolean fixedAbstained,
            boolean routerAbstained,
            boolean fixedPermissionViolation,
            boolean routerExternalCall,
            long fixedLatencyMs,
            long routerLatencyMs,
            int fixedInputTokens,
            int routerInputTokens
    ) {
        return new RagRouterAblationReplay(
                caseId, fixedRankedChunkIds, routerRankedChunkIds, fixedRoute, routerRoute,
                fixedAbstained, routerAbstained, fixedPermissionViolation, false, routerExternalCall,
                fixedLatencyMs, routerLatencyMs, fixedInputTokens, routerInputTokens,
                true, true
        );
    }
}
