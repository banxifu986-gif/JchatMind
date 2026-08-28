package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagIndependentBranchReplayLoaderTest {

    @Test
    void loadsFrozenReplayWithNonOriginalStandaloneQueryForFollowUpCase() throws Exception {
        RagIndependentBranchReplayLoader.FrozenReplay replay = RagIndependentBranchReplayLoader.load(
                "rag-eval/datasets/manifests/g2-pre-bm25-v1.json",
                "rag-eval/datasets/replays/g2-pre-bm25-v1-branches.jsonl"
        );

        assertThat(replay.cases()).hasSize(9);
        RagIndependentBranchReplayLoader.QueryReplay followUp = replay.cases().stream()
                .filter(item -> "g2-pre-bm25-v1-005".equals(item.caseId()))
                .findFirst()
                .orElseThrow();
        assertThat(followUp.originalQuery()).isEqualTo("那原问会被改写删除吗？");
        assertThat(followUp.retrievalQueries()).containsExactly(
                new RagIndependentBranchReplayLoader.RetrievalQuery(
                        "那原问会被改写删除吗？", "original"
                ),
                new RagIndependentBranchReplayLoader.RetrievalQuery(
                        "查询改写 standalone query 会保留原问作为主查询", "standalone"
                )
        );
        assertThat(followUp.conversation()).isNotEmpty();
        assertThat(followUp.kbScope()).containsExactly("g2-baseline-kb");
        assertThat(replay.inputSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void createsComparableRunsFromTheSameFrozenReplay() throws Exception {
        RagIndependentBranchReplayLoader.FrozenReplay replay = RagIndependentBranchReplayLoader.load(
                "rag-eval/datasets/manifests/g2-pre-bm25-v1.json",
                "rag-eval/datasets/replays/g2-pre-bm25-v1-branches.jsonl"
        );

        RagIndependentBranchReplayRunner runner = new RagIndependentBranchReplayRunner();
        List<RagIndependentBranchEvaluator.VariantRun> runs = runner.run(
                replay,
                query -> query.goldChunkIds().stream().toList()
        );

        assertThat(runs).extracting(RagIndependentBranchEvaluator.VariantRun::variant)
                .containsExactly("R0", "R1", "R2");
        assertThat(runs).allSatisfy(run -> {
            assertThat(run.queryReplays()).hasSize(9);
            assertThat(run.fingerprint().datasetId()).isEqualTo("g2-pre-bm25-v1");
            assertThat(run.fingerprint().topK()).isEqualTo(10);
            assertThat(run.fingerprint().candidateBudget()).isEqualTo(50);
        });

        RagIndependentBranchEvaluator.QueryReplay r0FollowUp = replay(runs.get(0), "g2-pre-bm25-v1-005");
        RagIndependentBranchEvaluator.QueryReplay r1FollowUp = replay(runs.get(1), "g2-pre-bm25-v1-005");
        RagIndependentBranchEvaluator.QueryReplay r2FollowUp = replay(runs.get(2), "g2-pre-bm25-v1-005");
        assertThat(r0FollowUp.branchDiagnostics())
                .extracting(RagIndependentBranchEvaluator.BranchDiagnostic::branch)
                .containsExactly("current-flat");
        assertThat(r1FollowUp.branchDiagnostics())
                .extracting(RagIndependentBranchEvaluator.BranchDiagnostic::branch)
                .containsExactly("dense-original", "sparse-original");
        assertThat(r2FollowUp.branchDiagnostics())
                .extracting(RagIndependentBranchEvaluator.BranchDiagnostic::branch)
                .containsExactly("dense-original", "sparse-original", "expanded-query");
        assertThat(replay(runs.get(2), "g2-pre-bm25-v1-006").branchDiagnostics())
                .extracting(RagIndependentBranchEvaluator.BranchDiagnostic::branch)
                .containsExactly("dense-original", "sparse-original");
        assertThat(replay(runs.get(2), "g2-pre-bm25-v1-007").rankedChunkIds()).isEmpty();

        Path reportPath = Path.of("target", "rag-eval", "three-branch", "g2-pre-bm25-v1-development.json");
        runner.writeReport(replay, runs, reportPath);
        assertThat(Files.readString(reportPath))
                .contains("\"datasetId\" : \"g2-pre-bm25-v1\"")
                .contains("\"queryReplaySha256\" : \"" + replay.inputSha256() + "\"");
    }

    private RagIndependentBranchEvaluator.QueryReplay replay(
            RagIndependentBranchEvaluator.VariantRun run,
            String caseId
    ) {
        return run.queryReplays().stream()
                .filter(item -> caseId.equals(item.caseId()))
                .findFirst()
                .orElseThrow();
    }
}
