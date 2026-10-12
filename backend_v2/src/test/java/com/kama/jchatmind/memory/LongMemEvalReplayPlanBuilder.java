package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LongMemEvalReplayPlanBuilder {

    private LongMemEvalReplayPlanBuilder() {
    }

    static ReplayPlan build(JsonNode manifest) {
        if (manifest == null || !"longmemeval-30-v1".equals(manifest.path("datasetId").asText())
                || !"candidate".equals(manifest.path("status").asText())
                || manifest.path("releaseEligible").asBoolean(true)
                || !"pending".equals(manifest.path("reviewStatus").asText())
                || !manifest.path("cases").isArray()
                || manifest.path("caseCount").asInt(-1) != manifest.path("cases").size()) {
            throw new IllegalArgumentException("LongMemEval candidate manifest 无效或已越过 pending 状态");
        }

        List<ReplayCase> replayCases = new ArrayList<>(manifest.path("cases").size());
        Set<String> caseIds = new HashSet<>();
        for (JsonNode candidateCase : manifest.path("cases")) {
            String caseId = requiredText(candidateCase, "caseId");
            if (!caseIds.add(caseId) || !"pending".equals(candidateCase.path("reviewStatus").asText())) {
                throw new IllegalArgumentException("LongMemEval candidate case 重复或不在 pending 状态: " + caseId);
            }
            replayCases.add(buildCase(candidateCase, caseId));
        }
        return new ReplayPlan(manifest.path("datasetId").asText(), List.copyOf(replayCases));
    }

    private static ReplayCase buildCase(JsonNode candidateCase, String caseId) {
        String categoryId = requiredText(candidateCase, "categoryId");
        JsonNode sourceRecord = candidateCase.path("sourceRecord");
        if (!caseId.equals(sourceRecord.path("question_id").asText())
                || !sourceRecord.path("question").isTextual()
                || !sourceRecord.path("question_date").isTextual()
                || !sourceRecord.hasNonNull("answer")) {
            throw new IllegalArgumentException("LongMemEval candidate sourceRecord 与 case metadata 不一致: " + caseId);
        }

        JsonNode sessions = sourceRecord.path("haystack_sessions");
        JsonNode sessionIds = sourceRecord.path("haystack_session_ids");
        JsonNode dates = sourceRecord.path("haystack_dates");
        JsonNode occurrences = candidateCase.path("sessionOccurrences");
        if (!sessions.isArray() || !sessionIds.isArray() || !dates.isArray() || !occurrences.isArray()
                || sessions.size() != sessionIds.size()
                || sessions.size() != dates.size()
                || sessions.size() != occurrences.size()) {
            throw new IllegalArgumentException("LongMemEval candidate session occurrence arrays 不一致: " + caseId);
        }

        List<ReplaySession> replaySessions = new ArrayList<>(sessions.size());
        List<EvidenceMessageRef> markedEvidence = new ArrayList<>();
        for (int sessionIndex = 0; sessionIndex < sessions.size(); sessionIndex++) {
            JsonNode sessionId = sessionIds.get(sessionIndex);
            JsonNode date = dates.get(sessionIndex);
            JsonNode occurrence = occurrences.get(sessionIndex);
            JsonNode sourceTurns = sessions.get(sessionIndex);
            if (!sessionId.isTextual() || !date.isTextual() || !sourceTurns.isArray()
                    || occurrence.path("occurrenceIndex").asInt(-1) != sessionIndex
                    || !caseId.concat("#").concat(Integer.toString(sessionIndex))
                    .equals(occurrence.path("occurrenceKey").asText())
                    || !sessionId.asText().equals(occurrence.path("sourceSessionId").asText())
                    || !date.asText().equals(occurrence.path("date").asText())) {
                throw new IllegalArgumentException("LongMemEval session occurrence mapping 无效: " + caseId);
            }

            List<ReplayMessage> messages = new ArrayList<>(sourceTurns.size());
            for (int messageIndex = 0; messageIndex < sourceTurns.size(); messageIndex++) {
                JsonNode turn = sourceTurns.get(messageIndex);
                if (!turn.isObject() || !turn.path("role").isTextual() || !turn.path("content").isTextual()
                        || (turn.has("has_answer") && !turn.path("has_answer").isBoolean())) {
                    throw new IllegalArgumentException("LongMemEval source message 无效: " + caseId);
                }
                messages.add(new ReplayMessage(
                        messageIndex,
                        turn.path("role").asText(),
                        turn.path("content").asText()
                ));
                if (turn.path("has_answer").asBoolean(false)) {
                    markedEvidence.add(new EvidenceMessageRef(sessionIndex, sessionId.asText(), messageIndex));
                }
            }
            replaySessions.add(new ReplaySession(
                    sessionIndex,
                    occurrence.path("occurrenceKey").asText(),
                    sessionId.asText(),
                    date.asText(),
                    List.copyOf(messages)
            ));
        }

        List<String> answerSessionIds = textArray(sourceRecord.path("answer_session_ids"), caseId);
        List<EvidenceMessageRef> evidenceMessageRefs = parseEvidenceRefs(
                candidateCase.path("evidenceMessageRefs"), caseId
        );
        if (!markedEvidence.equals(evidenceMessageRefs)) {
            throw new IllegalArgumentException("LongMemEval evidenceMessageRefs 与 source has_answer 标记不一致: "
                    + caseId);
        }

        TargetQuestion target = new TargetQuestion(
                sourceRecord.path("question").asText(),
                sourceRecord.path("answer").deepCopy(),
                sourceRecord.path("question_date").asText(),
                sourceRecord.path("question_type").asText()
        );
        GoldEvidence gold = new GoldEvidence(
                answerSessionIds,
                List.copyOf(evidenceMessageRefs),
                caseId.endsWith("_abs")
        );
        return new ReplayCase(caseId, categoryId, List.copyOf(replaySessions), target, gold);
    }

    private static List<EvidenceMessageRef> parseEvidenceRefs(JsonNode refs, String caseId) {
        if (!refs.isArray()) {
            throw new IllegalArgumentException("LongMemEval evidenceMessageRefs 缺失: " + caseId);
        }
        List<EvidenceMessageRef> result = new ArrayList<>(refs.size());
        for (JsonNode ref : refs) {
            if (ref.path("occurrenceIndex").asInt(-1) < 0
                    || !StringUtils.hasText(ref.path("sourceSessionId").asText())
                    || ref.path("messageIndex").asInt(-1) < 0) {
                throw new IllegalArgumentException("LongMemEval evidenceMessageRef 无效: " + caseId);
            }
            result.add(new EvidenceMessageRef(
                    ref.path("occurrenceIndex").asInt(),
                    ref.path("sourceSessionId").asText(),
                    ref.path("messageIndex").asInt()
            ));
        }
        return List.copyOf(result);
    }

    private static List<String> textArray(JsonNode values, String caseId) {
        if (!values.isArray()) {
            throw new IllegalArgumentException("LongMemEval answer_session_ids 缺失: " + caseId);
        }
        List<String> result = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("LongMemEval answer_session_id 无效: " + caseId);
            }
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("LongMemEval candidate manifest 缺少字段: " + field);
        }
        return value;
    }

    record ReplayPlan(String datasetId, List<ReplayCase> cases) {
    }

    record ReplayCase(
            String caseId,
            String categoryId,
            List<ReplaySession> sessions,
            TargetQuestion target,
            GoldEvidence gold
    ) {
    }

    record ReplaySession(
            int occurrenceIndex,
            String occurrenceKey,
            String sourceSessionId,
            String date,
            List<ReplayMessage> messages
    ) {
    }

    record ReplayMessage(int messageIndex, String role, String content) {
    }

    record TargetQuestion(String question, JsonNode answer, String questionDate, String questionType) {
    }

    record GoldEvidence(
            List<String> answerSessionIds,
            List<EvidenceMessageRef> evidenceMessageRefs,
            boolean shouldAbstain
    ) {
    }

    record EvidenceMessageRef(int occurrenceIndex, String sourceSessionId, int messageIndex) {
    }
}
