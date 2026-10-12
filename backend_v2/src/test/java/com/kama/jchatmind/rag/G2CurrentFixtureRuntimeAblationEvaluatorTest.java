package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentFixtureRuntimeAblationEvaluatorTest {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";

    @Test
    void comparesRetrievalEvidenceAndRuntimeCostWithoutClaimingAnswerQuality() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        G2CurrentFixtureRuntimeAblationEvaluator.RuntimeReport report =
                new G2CurrentFixtureRuntimeAblationEvaluator().evaluate(profile, matchingReplays(profile));

        assertThat(report.caseCount()).isEqualTo(20);
        assertThat(report.answerableCaseCount()).isEqualTo(18);
        assertThat(report.noAnswerCaseCount()).isEqualTo(2);
        assertThat(report.fixed().recallAt5()).isEqualTo(1D);
        assertThat(report.router().recallAt5()).isEqualTo(1D);
        assertThat(report.router().goldEvidenceCoverageAt10()).isEqualTo(1D);
        assertThat(report.router().routeViolations()).isZero();
        assertThat(report.gate().verdict()).isEqualTo("eligible_for_router_runtime_ab");
        assertThat(report.answerQualityMeasured()).isFalse();
        assertThat(report.answerCitationAccuracyMeasured()).isFalse();
        JsonNode reportJson = new ObjectMapper().valueToTree(report);
        assertThat(reportJson.path("caseReplays")).hasSize(20);
        assertThat(reportJson.path("caseReplays").get(0).path("fixed").path("selectedTopK").asInt())
                .isEqualTo(10);
        assertThat(reportJson.path("caseReplays").get(0).path("router").path("selectedTopK").asInt())
                .isEqualTo(3);
    }

    @Test
    void blocksQualityLatencyCostRouteAndPermissionRegressions() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        List<G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay> replays = new ArrayList<>();
        for (int index = 0; index < profile.cases().size(); index++) {
            G2CurrentFixtureCase item = profile.cases().get(index);
            G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm fixed = new G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm(
                    "PRIVATE_RAG", 10, item.goldChunkIds(), 100, item.shouldAbstain() ? 0 : 500, false, false
            );
            G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm router = new G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm(
                    index == 0 ? "ABSTAIN" : item.expectedRoute(),
                    index == 0 ? 0 : 3,
                    index == 0 ? List.of() : item.goldChunkIds(),
                    116,
                    item.shouldAbstain() ? 0 : 600,
                    index == 0,
                    index == 0
            );
            replays.add(new G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay(item.caseId(), fixed, router));
        }

        G2CurrentFixtureRuntimeAblationEvaluator.RuntimeReport report =
                new G2CurrentFixtureRuntimeAblationEvaluator().evaluate(profile, replays);

        assertThat(report.gate().verdict()).isEqualTo("inconclusive");
        assertThat(report.gate().reasons()).contains(
                "router_retrieval_quality_below_fixed_chain",
                "router_p95_latency_above_15_percent",
                "router_context_cost_above_10_percent",
                "router_route_mismatch",
                "router_permission_violations",
                "router_external_calls"
        );
    }

    @Test
    void blocksExternalCallsInEitherComparisonArm() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        List<G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay> replays =
                new ArrayList<>(matchingReplays(profile));
        G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay first = replays.get(0);
        G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm fixed = first.fixed();
        replays.set(0, new G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay(
                first.caseId(),
                new G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm(
                        fixed.route(),
                        fixed.selectedTopK(),
                        fixed.rankedChunkIds(),
                        fixed.latencyMs(),
                        fixed.contextCharacters(),
                        fixed.permissionViolation(),
                        true
                ),
                first.router()
        ));

        G2CurrentFixtureRuntimeAblationEvaluator.RuntimeReport report =
                new G2CurrentFixtureRuntimeAblationEvaluator().evaluate(profile, replays);

        assertThat(report.fixed().externalCalls()).isEqualTo(1);
        assertThat(report.gate().verdict()).isEqualTo("inconclusive");
        assertThat(report.gate().reasons()).contains("fixed_chain_external_calls");
    }

    @Test
    void requiresOnePairedReplayForEveryFrozenCase() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        List<G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay> replays = matchingReplays(profile);

        assertThatThrownBy(() -> new G2CurrentFixtureRuntimeAblationEvaluator()
                .evaluate(profile, replays.subList(1, replays.size())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAProfileWhoseFrozenRouteContractWasDerivedFromAChangedRouter() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        List<G2CurrentFixtureCase> cases = new ArrayList<>(profile.cases());
        G2CurrentFixtureCase first = cases.get(0);
        cases.set(0, new G2CurrentFixtureCase(
                first.caseId(),
                first.query(),
                first.queryType(),
                first.goldChunkIds(),
                first.sourceDocumentIds(),
                first.shouldAbstain(),
                first.citationRequired(),
                "HYBRID_RAG"
        ));
        G2CurrentFixtureProfile changedProfile = new G2CurrentFixtureProfile(
                profile.datasetId(),
                profile.caseCount(),
                profile.answerableCaseCount(),
                profile.abstentionCaseCount(),
                profile.followUpCaseCount(),
                profile.crossDocumentCaseCount(),
                cases,
                profile.routeCounts()
        );

        assertThatThrownBy(() -> new G2CurrentFixtureRuntimeAblationEvaluator()
                .evaluate(changedProfile, matchingReplays(changedProfile)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay> matchingReplays(
            G2CurrentFixtureProfile profile
    ) {
        return profile.cases().stream().map(item -> {
            int contextCharacters = item.shouldAbstain() ? 0 : 500;
            List<String> rankedChunkIds = item.goldChunkIds();
            return new G2CurrentFixtureRuntimeAblationEvaluator.CaseReplay(
                    item.caseId(),
                    new G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm(
                            "PRIVATE_RAG", 10, rankedChunkIds, 100, contextCharacters, false, false
                    ),
                    new G2CurrentFixtureRuntimeAblationEvaluator.RuntimeArm(
                            item.expectedRoute(), 3, rankedChunkIds, 110,
                            Math.max(0, contextCharacters - 50), false, false
                    )
            );
        }).toList();
    }
}
