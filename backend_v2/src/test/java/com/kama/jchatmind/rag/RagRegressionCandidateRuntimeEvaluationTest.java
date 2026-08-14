package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateRuntimeEvaluationTest {

    private static final String MAPPING_PATH_PROPERTY = "rag.eval.runtime.mapping-path";
    private static final String REPLAY_PATH_PROPERTY = "rag.eval.runtime.replay-path";

    @Test
    void evaluatesApprovedRuntimeCandidateReplayOnlyWhenExplicitFilesAreProvided() throws Exception {
        String mappingPathValue = System.getProperty(MAPPING_PATH_PROPERTY);
        String replayPathValue = System.getProperty(REPLAY_PATH_PROPERTY);
        Assumptions.assumeTrue(mappingPathValue != null && !mappingPathValue.isBlank()
                        && replayPathValue != null && !replayPathValue.isBlank(),
                "未显式提供运行期 UUID mapping/replay 文件，跳过 L2 候选评测");

        RagRegressionCandidateDataset dataset = RagRegressionCandidateDatasetLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate.json"
        );
        RagRegressionCandidateRuntimeEvaluationReport report = new RagRegressionCandidateRuntimeEvaluationRunner().evaluateAndWrite(
                dataset,
                Path.of(mappingPathValue),
                Path.of(replayPathValue),
                Path.of("target", "rag-eval", "candidates", "regression-v1-candidate-runtime-report.json")
        );

        assertTrue(report.evaluatedCases() > 0);
    }
}
