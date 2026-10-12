package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LongMemEvalBlindReviewDecisionLoader {

    private LongMemEvalBlindReviewDecisionLoader() {
    }

    static List<ResolvedDecision> resolve(JsonNode privateMapping, JsonNode decisionFile) {
        if (privateMapping == null || decisionFile == null
                || privateMapping.path("schemaVersion").asInt(-1) != 1
                || decisionFile.path("schemaVersion").asInt(-1) != 1
                || !"private".equals(privateMapping.path("visibility").asText())
                || !StringUtils.hasText(privateMapping.path("batchId").asText())
                || !privateMapping.path("batchId").asText().equals(decisionFile.path("batchId").asText())
                || !hasOnlyFields(decisionFile, Set.of("schemaVersion", "batchId", "decisions"))
                || !decisionFile.path("decisions").isArray()
                || !privateMapping.path("items").isArray()
                || decisionFile.path("decisions").size() != privateMapping.path("items").size()) {
            throw new IllegalArgumentException("LongMemEval blind review decision file metadata 不匹配或不完整");
        }

        Map<String, PrivateMappingEntry> mappingByReviewId = new LinkedHashMap<>();
        Set<String> candidateIds = new HashSet<>();
        for (JsonNode mapping : privateMapping.path("items")) {
            if (!mapping.isObject()
                    || !hasOnlyFields(mapping, Set.of(
                            "reviewItemId", "caseId", "candidateId", "evidenceMessageId"
                    ))) {
                throw new IllegalArgumentException("LongMemEval private review mapping schema 无效");
            }
            PrivateMappingEntry entry = new PrivateMappingEntry(
                    requiredText(mapping, "reviewItemId"),
                    requiredText(mapping, "caseId"),
                    requiredText(mapping, "candidateId"),
                    requiredText(mapping, "evidenceMessageId")
            );
            if (mappingByReviewId.putIfAbsent(entry.reviewItemId(), entry) != null
                    || !candidateIds.add(entry.candidateId())) {
                throw new IllegalArgumentException("LongMemEval private review mapping ID 重复");
            }
        }

        Map<String, Decision> decisionsByReviewId = new LinkedHashMap<>();
        for (JsonNode decision : decisionFile.path("decisions")) {
            if (!decision.isObject() || !hasOnlyFields(decision, Set.of("reviewItemId", "decision"))) {
                throw new IllegalArgumentException("LongMemEval blind review decision schema 无效");
            }
            String reviewItemId = requiredText(decision, "reviewItemId");
            Decision value;
            try {
                value = Decision.valueOf(requiredText(decision, "decision"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("LongMemEval blind review decision 仅允许 CONFIRM/DISCARD", exception);
            }
            if (decisionsByReviewId.putIfAbsent(reviewItemId, value) != null) {
                throw new IllegalArgumentException("LongMemEval blind review decision ID 重复: " + reviewItemId);
            }
        }

        if (!decisionsByReviewId.keySet().equals(mappingByReviewId.keySet())) {
            throw new IllegalArgumentException("LongMemEval blind review decisions 缺项或含未知 reviewItemId");
        }
        List<ResolvedDecision> result = new ArrayList<>(mappingByReviewId.size());
        for (PrivateMappingEntry mapping : mappingByReviewId.values()) {
            result.add(new ResolvedDecision(
                    mapping.reviewItemId(),
                    mapping.caseId(),
                    mapping.candidateId(),
                    mapping.evidenceMessageId(),
                    decisionsByReviewId.get(mapping.reviewItemId())
            ));
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("LongMemEval blind review decision 缺少字段: " + field);
        }
        return value;
    }

    private static boolean hasOnlyFields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) {
            return false;
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                return false;
            }
        }
        return true;
    }

    enum Decision {
        CONFIRM,
        DISCARD
    }

    record ResolvedDecision(
            String reviewItemId,
            String caseId,
            String candidateId,
            String evidenceMessageId,
            Decision decision
    ) {
    }

    private record PrivateMappingEntry(
            String reviewItemId,
            String caseId,
            String candidateId,
            String evidenceMessageId
    ) {
    }
}
