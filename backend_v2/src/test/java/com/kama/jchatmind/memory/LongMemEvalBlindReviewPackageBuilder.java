package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class LongMemEvalBlindReviewPackageBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> MEMORY_TYPES = Set.of(
            "BACKGROUND", "PREFERENCE", "CONSTRAINT", "GOAL", "FACT"
    );
    private static final Set<String> IMPORTANCE_VALUES = Set.of("high", "medium", "low");

    private LongMemEvalBlindReviewPackageBuilder() {
    }

    static Artifacts build(
            String datasetId,
            UUID batchId,
            Set<String> verifiedCaseIds,
            List<CandidateForReview> candidates
    ) {
        if (!"longmemeval-30-v1".equals(datasetId) || batchId == null
                || verifiedCaseIds == null || verifiedCaseIds.isEmpty()
                || candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("LongMemEval blind review package input 不完整");
        }

        List<PreparedItem> prepared = new ArrayList<>(candidates.size());
        Set<String> candidateIds = new HashSet<>();
        Set<String> reviewItemIds = new HashSet<>();
        for (CandidateForReview candidate : candidates) {
            if (candidate == null
                    || !StringUtils.hasText(candidate.caseId())
                    || !verifiedCaseIds.contains(candidate.caseId())
                    || !StringUtils.hasText(candidate.candidateId())
                    || !candidateIds.add(candidate.candidateId())
                    || !MEMORY_TYPES.contains(candidate.memoryType())
                    || !IMPORTANCE_VALUES.contains(candidate.importance())
                    || !StringUtils.hasText(candidate.content()) || candidate.content().length() > 300
                    || !StringUtils.hasText(candidate.evidenceMessageId())
                    || !StringUtils.hasText(candidate.evidenceText()) || candidate.evidenceText().length() > 500
                    || !UserMemoryCandidate.STATUS_PENDING.equals(candidate.status())) {
                throw new IllegalArgumentException("LongMemEval blind review candidate 无效或不在授权/待审范围");
            }
            String reviewItemId = UUID.nameUUIDFromBytes(
                    (batchId + "\u0000" + candidate.candidateId()).getBytes(StandardCharsets.UTF_8)
            ).toString();
            if (!reviewItemIds.add(reviewItemId)) {
                throw new IllegalArgumentException("LongMemEval blind review item ID 重复");
            }
            prepared.add(new PreparedItem(reviewItemId, candidate));
        }
        prepared.sort(Comparator.comparing(PreparedItem::reviewItemId));

        ObjectNode reviewerPacket = OBJECT_MAPPER.createObjectNode();
        reviewerPacket.put("schemaVersion", 1);
        reviewerPacket.put("datasetId", datasetId);
        reviewerPacket.put("batchId", batchId.toString());
        reviewerPacket.put("reviewStatus", "pending");
        ArrayNode reviewItems = reviewerPacket.putArray("items");

        ObjectNode privateMapping = OBJECT_MAPPER.createObjectNode();
        privateMapping.put("schemaVersion", 1);
        privateMapping.put("batchId", batchId.toString());
        privateMapping.put("visibility", "private");
        ArrayNode mappings = privateMapping.putArray("items");

        for (PreparedItem item : prepared) {
            CandidateForReview candidate = item.candidate();
            ObjectNode reviewItem = reviewItems.addObject();
            reviewItem.put("reviewItemId", item.reviewItemId());
            reviewItem.put("memoryType", candidate.memoryType());
            reviewItem.put("content", candidate.content());
            reviewItem.put("importance", candidate.importance());
            reviewItem.put("evidenceText", candidate.evidenceText());
            reviewItem.putNull("decision");

            ObjectNode mapping = mappings.addObject();
            mapping.put("reviewItemId", item.reviewItemId());
            mapping.put("caseId", candidate.caseId());
            mapping.put("candidateId", candidate.candidateId());
            mapping.put("evidenceMessageId", candidate.evidenceMessageId());
        }
        return new Artifacts(reviewerPacket, privateMapping);
    }

    record CandidateForReview(
            String caseId,
            String candidateId,
            String memoryType,
            String content,
            String importance,
            String evidenceMessageId,
            String evidenceText,
            String status
    ) {
    }

    record Artifacts(ObjectNode reviewerPacket, ObjectNode privateMapping) {
    }

    private record PreparedItem(String reviewItemId, CandidateForReview candidate) {
    }
}
