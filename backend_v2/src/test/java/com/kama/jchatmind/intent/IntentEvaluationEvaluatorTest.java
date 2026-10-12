package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationEvaluatorTest {

    @Test
    void shouldEvaluateRuleRouterWithCoverageAndMacroF1() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );

        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());

        assertThat(report.totalCases()).isEqualTo(20);
        assertThat(report.datasetId()).isEqualTo(dataset.manifest().datasetId());
        assertThat(report.datasetSourceSha256()).isEqualTo(dataset.manifest().caseSha256());
        assertThat(report.datasetCaseSha256()).isEqualTo(dataset.effectiveCaseSha256());
        assertThat(report.decisionVersion()).isEqualTo(IntentRouter.DECISION_VERSION);
        assertThat(report.policyVersion()).isEqualTo(IntentRouter.POLICY_VERSION);
        assertThat(report.runConfiguration().mode()).isEqualTo("rule-only");
        assertThat(report.evaluatedCases()).isEqualTo(20);
        assertThat(report.skippedCases()).isZero();
        assertThat(report.correctCases()).isEqualTo(20);
        assertThat(report.accuracy()).isEqualTo(1D);
        assertThat(report.macroF1()).isEqualTo(1D);
        assertThat(report.decisionContractMatchCases()).isEqualTo(18);
        assertThat(report.decisionContractMatchRate()).isEqualTo(0.90D);
        assertThat(report.abstainAccuracy()).isEqualTo(1D);
        assertThat(report.clarificationAccuracy()).isEqualTo(1D);
        assertThat(report.sideEffectFalsePositiveCases()).isZero();
        assertThat(report.highRiskFalsePositiveCases()).isZero();
        assertThat(report.perIntentMetrics()).containsKey("KNOWLEDGE_QA");
        assertThat(report.perIntentMetrics().get("KNOWLEDGE_QA").precision()).isEqualTo(1D);
        assertThat(report.perIntentMetrics().get("KNOWLEDGE_QA").recall()).isEqualTo(1D);
        assertThat(report.confusionMatrix())
                .containsEntry("FILE_READ->FILE_READ", 1)
                .containsEntry("GENERAL_EXPLANATION->GENERAL_EXPLANATION", 3);
    }

    @Test
    void shouldProduceDeterministicReportForSameFixture() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );

        IntentEvaluationReport first = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());
        IntentEvaluationReport second = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());

        assertThat(first).isEqualTo(second);
    }

    @Test
    void shouldClassifyEachCaseOnlyOnceWhenBuildingAggregateAndSplitMetrics() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        AtomicInteger decisionCalls = new AtomicInteger();
        IntentRouter countingRouter = new IntentRouter() {
            @Override
            public IntentDecision decide(String query, String previousUserQuery) {
                decisionCalls.incrementAndGet();
                return super.decide(query, previousUserQuery);
            }
        };

        IntentEvaluationEvaluator.evaluate(dataset, countingRouter);

        assertThat(decisionCalls).hasValue(dataset.cases().size());
    }

    @Test
    void shouldReportDevelopmentAndTestMetricsSeparately() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/intent-candidate-v1.json"
        );

        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());

        assertThat(report.splitMetrics()).containsKeys("dev", "test");
        assertThat(report.splitMetrics().get("dev").totalCases()).isEqualTo(200);
        assertThat(report.splitMetrics().get("test").totalCases()).isEqualTo(100);
        assertThat(report.splitMetrics().get("dev").evaluatedCases()).isEqualTo(200);
        assertThat(report.splitMetrics().get("test").evaluatedCases()).isEqualTo(100);
        assertThat(report.splitMetrics().get("test").perIntentMetrics()).containsKey("EXTERNAL_LOOKUP");
    }

    @Test
    void shouldEvaluateTheConfiguredFallbackRouterAgainstTheSameGoldCases() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        AtomicInteger modelCalls = new AtomicInteger();
        IntentFallbackRouter fallbackRouter = new IntentFallbackRouter(
                new IntentRouter(),
                new ObjectMapper(),
                query -> {
                    modelCalls.incrementAndGet();
                    return "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.94,\"margin\":0.6}";
                },
                new IntentRoutingProperties(),
                false,
                IntentDecisionTelemetry.noop()
        );

        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, fallbackRouter);

        assertThat(report.totalCases()).isEqualTo(20);
        assertThat(report.evaluatedCases()).isEqualTo(20);
        assertThat(modelCalls.get()).isPositive();
        assertThat(report.sideEffectFalsePositiveCases()).isZero();
        assertThat(report.highRiskFalsePositiveCases()).isZero();
    }

    @Test
    void shouldSeparateEvaluatedSkippedAndFailedCasesWithReasons() throws Exception {
        IntentEvaluationDataset fixture = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        List<IntentEvaluationCase> cases = new ArrayList<>(fixture.cases());
        cases.set(0, withQueryAndSkipReason(cases.get(0), "intentionally skipped", "unsupported_fixture_case"));
        cases.set(1, withQueryAndSkipReason(cases.get(1), "throw-on-evaluation", null));
        IntentEvaluationDataset mixedDataset = new IntentEvaluationDataset(
                fixture.manifest(),
                List.copyOf(cases),
                fixture.effectiveCaseSha256()
        );
        IntentRouter router = new IntentRouter() {
            @Override
            public IntentDecision decide(String query, String previousUserQuery) {
                if ("throw-on-evaluation".equals(query)) {
                    throw new IllegalStateException("synthetic evaluation failure");
                }
                return super.decide(query, previousUserQuery);
            }
        };

        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(mixedDataset, router);

        assertThat(report.totalCases()).isEqualTo(20);
        assertThat(report.evaluatedCases()).isEqualTo(18);
        assertThat(report.skippedCases()).isEqualTo(1);
        assertThat(report.failedCases()).isEqualTo(1);
        assertThat(report.skipReasons()).containsEntry("unsupported_fixture_case", 1);
        assertThat(report.failureReasons()).containsEntry("router_exception", 1);
    }

    private IntentEvaluationCase withQueryAndSkipReason(
            IntentEvaluationCase item,
            String query,
            String skipReason
    ) {
        return new IntentEvaluationCase(
                item.caseId(),
                item.datasetId(),
                item.split(),
                query,
                item.previousUserQuery(),
                skipReason,
                item.expectedBusinessIntent(),
                item.expectedRoute(),
                item.expectedRiskLevel(),
                item.expectedCapabilities(),
                item.shouldClarify(),
                item.shouldAbstain(),
                item.tags(),
                item.reviewStatus()
        );
    }
}
