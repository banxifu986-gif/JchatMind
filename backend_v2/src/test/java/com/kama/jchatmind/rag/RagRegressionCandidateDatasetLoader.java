package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

final class RagRegressionCandidateDatasetLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagRegressionCandidateDatasetLoader() {
    }

    static RagRegressionCandidateDataset load(String resourcePath) throws IOException {
        try (InputStream inputStream = new ClassPathResource(resourcePath).getInputStream()) {
            RagRegressionCandidateDataset dataset = OBJECT_MAPPER.readValue(inputStream, RagRegressionCandidateDataset.class);
            validate(dataset);
            return dataset;
        }
    }

    static void validate(RagRegressionCandidateDataset dataset) {
        if (!dataset.datasetId().endsWith("-candidate") || !"candidate".equals(dataset.status())
                || !StringUtils.hasText(dataset.sourceKnowledgeBaseId()) || dataset.cases() == null || dataset.cases().isEmpty()) {
            throw new IllegalStateException("真实回归候选集元数据不符合规范");
        }
        Set<String> caseIds = new HashSet<>();
        for (RagRegressionCandidateCase item : dataset.cases()) {
            Set<String> expectedRetrievalGold = new HashSet<>();
            expectedRetrievalGold.add(item.logicalChunkId());
            expectedRetrievalGold.addAll(item.additionalGoldLogicalChunkIds());
            Set<String> actualRetrievalGold = new HashSet<>(item.retrievalGoldLogicalChunkIds());
            if (!caseIds.add(item.caseId()) || !StringUtils.hasText(item.query())
                    || !StringUtils.hasText(item.logicalChunkId()) || !StringUtils.hasText(item.logicalSectionPath())
                    || !StringUtils.hasText(item.sourceDocumentLogicalId())
                    || item.sourceDocumentSha256() == null || !item.sourceDocumentSha256().matches("[0-9a-f]{64}")
                    || item.conversation() == null || item.additionalGoldLogicalChunkIds() == null
                    || item.retrievalGoldLogicalChunkIds() == null
                    || item.conversation().stream().anyMatch(turn -> !StringUtils.hasText(turn.role()) || !StringUtils.hasText(turn.content()))
                    || item.additionalGoldLogicalChunkIds().contains(item.logicalChunkId())
                    || item.goldFacts() == null || item.shouldAbstain() == null
                    || (Boolean.TRUE.equals(item.shouldAbstain()) && (!Set.of("missing_evidence", "permission_denied", "out_of_scope")
                    .contains(item.abstentionReason()) || !item.goldFacts().isEmpty() || !item.retrievalGoldLogicalChunkIds().isEmpty()))
                    || (Boolean.FALSE.equals(item.shouldAbstain()) && (!StringUtils.hasText(String.join("", item.goldFacts()))
                    || StringUtils.hasText(item.abstentionReason())
                    || item.retrievalGoldLogicalChunkIds().size() != actualRetrievalGold.size()
                    || !actualRetrievalGold.equals(expectedRetrievalGold)))
                    || !isValidReviewMetadata(item)) {
                throw new IllegalStateException("真实回归候选 case 不符合规范: " + item.caseId());
            }
        }
    }

    private static boolean isValidReviewMetadata(RagRegressionCandidateCase item) {
        if (!StringUtils.hasText(item.createdBy())) {
            return false;
        }
        if ("candidate".equals(item.reviewStatus())) {
            return !StringUtils.hasText(item.reviewedBy()) && !StringUtils.hasText(item.reviewedAt());
        }
        return ("approved".equals(item.reviewStatus()) || "rejected".equals(item.reviewStatus()))
                && StringUtils.hasText(item.reviewedBy())
                && StringUtils.hasText(item.reviewedAt());
    }
}
