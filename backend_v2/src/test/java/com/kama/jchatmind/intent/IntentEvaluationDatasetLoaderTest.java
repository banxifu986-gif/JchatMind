package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentEvaluationDatasetLoaderTest {

    @Test
    void shouldLoadAndValidateFrozenIntentFixture() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );

        assertThat(dataset.manifest().datasetId()).isEqualTo("intent-fixture-fast-v1");
        assertThat(dataset.manifest().status()).isEqualTo("frozen");
        assertThat(dataset.manifest().fixtureOnly()).isTrue();
        assertThat(dataset.cases()).hasSize(20);
        assertThat(dataset.cases()).anyMatch(item -> item.expectedBusinessIntent() == BusinessIntent.ABSTAIN);
        assertThat(dataset.cases()).anyMatch(IntentEvaluationCase::shouldClarify);
    }

    @Test
    void shouldRejectFrozenManifestMissingRequiredFields() {
        List<IntentEvaluationDatasetManifest> invalidManifests = List.of(
                new IntentEvaluationDatasetManifest(null, "1", "cases.jsonl", "a".repeat(64), "frozen",
                        true, null, null, null, null),
                new IntentEvaluationDatasetManifest("intent-fixture-fast-v1", "2", "cases.jsonl",
                        "a".repeat(64), "frozen", true, null, null, null, null),
                new IntentEvaluationDatasetManifest("intent-fixture-fast-v1", "1", null, "a".repeat(64),
                        "frozen", true, null, null, null, null),
                new IntentEvaluationDatasetManifest("intent-fixture-fast-v1", "1", "cases.jsonl", null,
                        "frozen", true, null, null, null, null),
                new IntentEvaluationDatasetManifest("intent-fixture-fast-v1", "1", "cases.jsonl",
                        "a".repeat(64), "candidate", true, null, null, null, null)
        );

        invalidManifests.forEach(manifest -> assertThatThrownBy(
                () -> IntentEvaluationDatasetLoader.validateManifest(manifest, "synthetic-manifest.json")
        ).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void shouldRejectDuplicateCaseIds() {
        IntentEvaluationCase item = new IntentEvaluationCase(
                "duplicate-1",
                "intent-fixture-fast-v1",
                "test",
                "你好",
                null,
                null,
                BusinessIntent.DIRECT_CHAT,
                IntentRoute.DIRECT,
                IntentRiskLevel.NONE,
                List.of(),
                false,
                false,
                List.of("direct"),
                "approved"
        );

        assertThatThrownBy(() -> IntentEvaluationDatasetLoader.validateCases(
                List.of(item, item),
                "intent-fixture-fast-v1"
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldLoadThreeHundredCandidateCasesWithoutSeedFamilyLeakage() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/intent-candidate-v1.json"
        );
        Map<String, Integer> splitCounts = new HashMap<>();
        Map<String, Set<String>> familySplits = new HashMap<>();
        for (IntentEvaluationCase item : dataset.cases()) {
            splitCounts.merge(item.split(), 1, Integer::sum);
            item.tags().stream()
                    .filter(tag -> tag.startsWith("seed-family:"))
                    .forEach(tag -> familySplits.computeIfAbsent(tag, ignored -> new java.util.HashSet<>())
                            .add(item.split()));
        }

        assertThat(dataset.cases()).hasSize(300);
        assertThat(dataset.manifest().generatorVersion()).isEqualTo("seed-paraphrase-expansion-v4");
        assertThat(splitCounts).containsEntry("dev", 200).containsEntry("test", 100);
        assertThat(dataset.cases()).allMatch(item -> item.reviewStatus().equals("pending_review"));
        assertThat(dataset.cases())
                .filteredOn(item -> item.expectedBusinessIntent() == BusinessIntent.KNOWLEDGE_FOLLOW_UP)
                .hasSize(17)
                .allMatch(item -> item.previousUserQuery() != null && !item.previousUserQuery().isBlank());
        assertThat(familySplits.values()).allMatch(splits -> splits.size() == 1);

        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());
        assertThat(report.totalCases()).isEqualTo(300);
        assertThat(report.evaluatedCases()).isEqualTo(300);
        assertThat(report.accuracy()).isEqualTo(1D);
        assertThat(report.macroF1()).isEqualTo(1D);
        assertThat(report.decisionContractMatchRate()).isEqualTo(1D);
        assertThat(report.confusionMatrix()).isNotEmpty()
                .allSatisfy((key, count) -> assertThat(key.split("->")).containsExactly(key.split("->")[0], key.split("->")[0]));
        IntentEvaluationGateResult gate = IntentEvaluationReleaseGate.evaluate(report, dataset);
        assertThat(gate.eligible()).isFalse();
        assertThat(gate.blockers()).contains("dataset_not_frozen", "cases_not_fully_reviewed");
    }
}
