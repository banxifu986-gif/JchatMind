package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateSourceAnchorLoaderTest {

    @Test
    void loadsAnAnchorForEveryCandidateCaseAndRejectsOrphans() throws Exception {
        RagRegressionCandidateDataset dataset = RagRegressionCandidateDatasetLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate.json"
        );

        Map<String, RagRegressionCandidateSourceAnchor> anchors = RagRegressionCandidateSourceAnchorLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate-anchors.json", dataset
        );

        assertEquals(49, anchors.size());
        assertTrue(dataset.cases().stream().allMatch(item -> anchors.containsKey(item.logicalChunkId())));
        assertEquals("interview-qa", anchors.get("interview-qa#一、项目概览>用户认证实现#0").sourceDocumentLogicalId());
        assertEquals("agent-harness-candidate", anchors.get("agent-harness#3.人工审批#0").sourceDocumentLogicalId());
    }
}
