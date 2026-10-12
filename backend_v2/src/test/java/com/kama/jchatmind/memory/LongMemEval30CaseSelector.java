package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LongMemEval30CaseSelector {

    private static final String ABSTENTION_CATEGORY = "abstention";
    private static final String ABSTENTION_SUFFIX = "_abs";

    private LongMemEval30CaseSelector() {
    }

    static List<SelectedCaseReference> select(
            JsonNode projectSubset,
            List<LongMemEvalOfficialDatasetLoader.SourceReference> references
    ) {
        if (projectSubset == null || !projectSubset.isObject()
                || !"longmemeval-30-v1".equals(projectSubset.path("datasetId").asText())
                || !"candidate".equals(projectSubset.path("status").asText())
                || !"pending".equals(projectSubset.path("reviewStatus").asText())
                || projectSubset.path("caseCount").asInt() <= 0
                || projectSubset.path("selectionSeed").isMissingNode()
                || !projectSubset.path("categories").isArray()
                || references == null || references.isEmpty()) {
            throw new IllegalArgumentException("LongMemEval project subset pin 或 source reference 缺失");
        }

        long seed = projectSubset.path("selectionSeed").asLong();
        Map<String, Integer> perQuestionTypeCount = new HashMap<>();
        Map<String, String> categoryByQuestionType = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        Set<String> categoryIds = new HashSet<>();
        int requestedCaseCount = 0;
        String abstentionSuffix = null;
        for (JsonNode category : projectSubset.path("categories")) {
            String categoryId = category.path("id").asText();
            int count = category.path("count").asInt();
            if (categoryId.isBlank() || count <= 0 || !categoryIds.add(categoryId)) {
                throw new IllegalArgumentException("LongMemEval project category 无效或重复");
            }
            requestedCaseCount += count;
            categoryCounts.put(categoryId, count);
            if (ABSTENTION_CATEGORY.equals(categoryId)) {
                abstentionSuffix = category.path("questionIdSuffix").asText();
                if (!ABSTENTION_SUFFIX.equals(abstentionSuffix)
                        || !category.path("excludeFromOtherCategories").asBoolean()) {
                    throw new IllegalArgumentException("LongMemEval abstention category 必须使用互斥的 _abs id suffix");
                }
                continue;
            }

            JsonNode questionTypes = category.path("questionTypes");
            int countPerQuestionType = category.path("countPerQuestionType").asInt();
            if (!questionTypes.isArray() || questionTypes.isEmpty()
                    || countPerQuestionType <= 0
                    || questionTypes.size() * countPerQuestionType != count) {
                throw new IllegalArgumentException("LongMemEval project category questionType 分配无效: " + categoryId);
            }
            for (JsonNode questionType : questionTypes) {
                String type = questionType.asText();
                if (type.isBlank()
                        || categoryByQuestionType.putIfAbsent(type, categoryId) != null
                        || perQuestionTypeCount.putIfAbsent(type, countPerQuestionType) != null) {
                    throw new IllegalArgumentException("LongMemEval questionType 重复或缺失: " + type);
                }
            }
        }
        if (!categoryIds.contains(ABSTENTION_CATEGORY)
                || requestedCaseCount != projectSubset.path("caseCount").asInt()) {
            throw new IllegalArgumentException("LongMemEval project category 总数与 caseCount 不一致");
        }

        Set<String> sourceQuestionIds = new HashSet<>();
        Set<Integer> sourceIndexes = new HashSet<>();
        Map<String, List<RankedReference>> candidatesByType = new HashMap<>();
        List<RankedReference> abstentionCandidates = new ArrayList<>();
        for (LongMemEvalOfficialDatasetLoader.SourceReference reference : references) {
            if (reference == null || reference.questionId() == null || reference.questionId().isBlank()
                    || reference.questionType() == null || reference.questionType().isBlank()
                    || reference.sourceIndex() < 0
                    || !sourceQuestionIds.add(reference.questionId())
                    || !sourceIndexes.add(reference.sourceIndex())
                    || reference.abstention() != reference.questionId().endsWith(ABSTENTION_SUFFIX)) {
                throw new IllegalArgumentException("LongMemEval source reference identity 无效或重复");
            }
            String rank = rank(seed, reference.questionId());
            RankedReference ranked = new RankedReference(reference, rank);
            if (reference.abstention()) {
                abstentionCandidates.add(ranked);
            } else {
                if (!categoryByQuestionType.containsKey(reference.questionType())) {
                    throw new IllegalArgumentException("LongMemEval source questionType 未纳入互斥分层: "
                            + reference.questionType());
                }
                candidatesByType.computeIfAbsent(reference.questionType(), ignored -> new ArrayList<>())
                        .add(ranked);
            }
        }

        Comparator<RankedReference> order = Comparator.comparing(RankedReference::selectionRank)
                .thenComparing(item -> item.reference().questionId());
        candidatesByType.values().forEach(list -> list.sort(order));
        abstentionCandidates.sort(order);

        List<SelectedCaseReference> selected = new ArrayList<>(requestedCaseCount);
        for (JsonNode category : projectSubset.path("categories")) {
            String categoryId = category.path("id").asText();
            if (ABSTENTION_CATEGORY.equals(categoryId)) {
                addSelected(selected, categoryId, abstentionCandidates, categoryCounts.get(categoryId));
                continue;
            }
            int countPerQuestionType = category.path("countPerQuestionType").asInt();
            for (JsonNode questionType : category.path("questionTypes")) {
                String type = questionType.asText();
                List<RankedReference> candidates = candidatesByType.getOrDefault(type, List.of());
                addSelected(selected, categoryId, candidates, countPerQuestionType);
            }
        }

        if (selected.size() != requestedCaseCount
                || selected.stream().map(item -> item.sourceReference().questionId()).distinct().count()
                != requestedCaseCount) {
            throw new IllegalArgumentException("LongMemEval 选择结果 case 数量错误或 questionId 重复");
        }
        return List.copyOf(selected);
    }

    private static void addSelected(
            List<SelectedCaseReference> selected,
            String categoryId,
            List<RankedReference> candidates,
            int count
    ) {
        if (candidates.size() < count) {
            throw new IllegalArgumentException("LongMemEval category 样本不足: " + categoryId);
        }
        candidates.stream()
                .limit(count)
                .map(item -> new SelectedCaseReference(item.reference(), categoryId, item.selectionRank()))
                .forEach(selected::add);
    }

    private static String rank(long seed, String questionId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(questionId.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    record SelectedCaseReference(
            LongMemEvalOfficialDatasetLoader.SourceReference sourceReference,
            String categoryId,
            String selectionRank
    ) {
    }

    private record RankedReference(
            LongMemEvalOfficialDatasetLoader.SourceReference reference,
            String selectionRank
    ) {
    }
}
