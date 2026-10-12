package com.kama.jchatmind.memory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LongMemEvalOfficialDatasetLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> QUESTION_TYPES = Set.of(
            "single-session-user",
            "single-session-assistant",
            "single-session-preference",
            "multi-session",
            "knowledge-update",
            "temporal-reasoning"
    );

    private LongMemEvalOfficialDatasetLoader() {
    }

    static SourceSummary validateSource(Path sourcePath, JsonNode sourcePin) throws IOException {
        if (sourcePath == null || !Files.isRegularFile(sourcePath) || sourcePin == null) {
            throw new IllegalArgumentException("LongMemEval source file 或 source pin 缺失");
        }
        long expectedSize = sourcePin.path("sizeBytes").asLong(-1);
        String expectedSha256 = sourcePin.path("sha256").asText();
        int expectedRecordCount = sourcePin.path("recordCount").asInt(-1);
        JsonNode observedSchema = sourcePin.path("observedSchema");
        if (Files.size(sourcePath) != expectedSize
                || !expectedSha256.matches("[0-9a-f]{64}")
                || expectedRecordCount <= 0 || !observedSchema.isObject()
                || !observedSchema.path("questionTypeCounts").isObject()) {
            throw new IllegalStateException("LongMemEval source file size 或 source pin 字段不匹配");
        }
        Map<String, Integer> expectedQuestionTypeCounts = new HashMap<>();
        observedSchema.path("questionTypeCounts").fields().forEachRemaining(entry -> {
            if (!entry.getValue().canConvertToInt() || entry.getValue().asInt() < 0) {
                throw new IllegalStateException("LongMemEval source pin questionType count 无效");
            }
            expectedQuestionTypeCounts.put(entry.getKey(), entry.getValue().asInt());
        });

        MessageDigest digest = newSha256();
        Set<String> questionIds = new HashSet<>();
        Map<String, Integer> questionTypeCounts = new HashMap<>();
        List<SourceReference> caseReferences = new ArrayList<>();
        int abstentionCount = 0;
        int repeatedSessionIdCount = 0;
        int repeatedSessionDateDifferenceCount = 0;
        int repeatedSessionPayloadDifferenceCount = 0;
        int recordCount = 0;
        try (DigestInputStream inputStream = new DigestInputStream(Files.newInputStream(sourcePath), digest);
             JsonParser parser = OBJECT_MAPPER.getFactory().createParser(inputStream)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("LongMemEval source 顶层必须为 JSON array");
            }
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
                if (token != JsonToken.START_OBJECT) {
                    throw new IllegalStateException("LongMemEval source 每项必须为 JSON object");
                }
                JsonNode item = OBJECT_MAPPER.readTree(parser);
                RecordSummary recordSummary = validateRecord(item, questionIds, questionTypeCounts);
                String questionId = item.path("question_id").asText();
                caseReferences.add(new SourceReference(
                        questionId,
                        item.path("question_type").asText(),
                        questionId.endsWith("_abs"),
                        recordCount
                ));
                repeatedSessionIdCount += recordSummary.repeatedSessionIdCount();
                repeatedSessionDateDifferenceCount += recordSummary.repeatedSessionDateDifferenceCount();
                repeatedSessionPayloadDifferenceCount += recordSummary.repeatedSessionPayloadDifferenceCount();
                if (item.path("question_id").asText().endsWith("_abs")) {
                    abstentionCount++;
                }
                recordCount++;
            }
            if (parser.nextToken() != null) {
                throw new IllegalStateException("LongMemEval source JSON array 后存在额外内容");
            }
        }

        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        if (recordCount != expectedRecordCount || !expectedSha256.equals(actualSha256)) {
            throw new IllegalStateException("LongMemEval source record count 或 SHA-256 与 pin 不匹配");
        }
        if (abstentionCount != observedSchema.path("abstentionCount").asInt(-1)
                || !questionTypeCounts.equals(expectedQuestionTypeCounts)
                || !questionTypeCounts.keySet().equals(QUESTION_TYPES)
                || repeatedSessionIdCount != observedSchema.path("repeatedSessionIdOccurrences").asInt(-1)
                || repeatedSessionDateDifferenceCount
                != observedSchema.path("repeatedSessionIdDateDifferences").asInt(-1)
                || repeatedSessionPayloadDifferenceCount
                != observedSchema.path("repeatedSessionIdPayloadDifferences").asInt(-1)) {
            throw new IllegalStateException("LongMemEval source 官方类别或 abstention 数量不符合预期");
        }
        return new SourceSummary(
                Files.size(sourcePath),
                actualSha256,
                recordCount,
                abstentionCount,
                repeatedSessionIdCount,
                repeatedSessionDateDifferenceCount,
                repeatedSessionPayloadDifferenceCount,
                Map.copyOf(questionTypeCounts),
                List.copyOf(caseReferences)
        );
    }

    private static RecordSummary validateRecord(
            JsonNode item,
            Set<String> questionIds,
            Map<String, Integer> questionTypeCounts
    ) {
        String questionId = requiredText(item, "question_id");
        String questionType = requiredText(item, "question_type");
        if (!QUESTION_TYPES.contains(questionType) || !questionIds.add(questionId)
                || !item.path("question").isTextual() || item.path("question").asText().isBlank()
                || !item.hasNonNull("answer") || !item.path("question_date").isTextual()) {
            throw new IllegalStateException("LongMemEval source case 字段、question_type 或 question_id 无效: "
                    + questionId);
        }

        JsonNode sessions = item.path("haystack_sessions");
        JsonNode sessionIds = item.path("haystack_session_ids");
        JsonNode dates = item.path("haystack_dates");
        JsonNode answerSessionIds = item.path("answer_session_ids");
        if (!sessions.isArray() || !sessionIds.isArray() || !dates.isArray() || !answerSessionIds.isArray()
                || sessions.size() != sessionIds.size() || sessions.size() != dates.size()) {
            throw new IllegalStateException("LongMemEval source session arrays 无效: " + questionId);
        }

        Map<String, Integer> sessionIndexById = new HashMap<>();
        Map<String, SessionOccurrence> sessionById = new HashMap<>();
        int repeatedSessionIdCount = 0;
        int repeatedSessionDateDifferenceCount = 0;
        int repeatedSessionPayloadDifferenceCount = 0;
        for (int index = 0; index < sessions.size(); index++) {
            JsonNode sessionId = sessionIds.get(index);
            JsonNode date = dates.get(index);
            JsonNode session = sessions.get(index);
            if (!sessionId.isTextual() || !date.isTextual() || !session.isArray()) {
                throw new IllegalStateException("LongMemEval source session identity 无效: " + questionId
                        + " (sessionId=" + sessionId.getNodeType()
                        + ", date=" + date.getNodeType()
                        + ", session=" + session.getNodeType() + ")");
            }
            Integer firstIndex = sessionIndexById.putIfAbsent(sessionId.asText(), index);
            if (firstIndex != null) {
                repeatedSessionIdCount++;
                SessionOccurrence previous = sessionById.get(sessionId.asText());
                if (!previous.date().equals(date.asText())) {
                    repeatedSessionDateDifferenceCount++;
                }
                if (!previous.payload().equals(session)) {
                    repeatedSessionPayloadDifferenceCount++;
                }
            } else {
                sessionById.put(sessionId.asText(), new SessionOccurrence(date.asText(), session));
            }
            for (JsonNode turn : session) {
                if (!turn.isObject() || !turn.path("role").isTextual() || !turn.path("content").isTextual()
                        || (turn.has("has_answer") && !turn.path("has_answer").isBoolean())) {
                    throw new IllegalStateException("LongMemEval source session turn 无效: " + questionId);
                }
            }
        }
        for (JsonNode answerSessionId : answerSessionIds) {
            if (!answerSessionId.isTextual() || !sessionIndexById.containsKey(answerSessionId.asText())) {
                throw new IllegalStateException("LongMemEval source answer_session_id 未映射到 haystack: "
                        + questionId);
            }
        }
        questionTypeCounts.merge(questionType, 1, Integer::sum);
        return new RecordSummary(
                repeatedSessionIdCount,
                repeatedSessionDateDifferenceCount,
                repeatedSessionPayloadDifferenceCount
        );
    }

    private static String requiredText(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("LongMemEval source 缺少有效字段 " + field);
        }
        return value.asText();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    record SourceSummary(
            long sizeBytes,
            String sha256,
            int recordCount,
            int abstentionCount,
            int repeatedSessionIdCount,
            int repeatedSessionDateDifferenceCount,
            int repeatedSessionPayloadDifferenceCount,
            Map<String, Integer> questionTypeCounts,
            List<SourceReference> caseReferences
    ) {
    }

    record SourceReference(String questionId, String questionType, boolean abstention, int sourceIndex) {
    }

    private record SessionOccurrence(String date, JsonNode payload) {
    }

    private record RecordSummary(
            int repeatedSessionIdCount,
            int repeatedSessionDateDifferenceCount,
            int repeatedSessionPayloadDifferenceCount
    ) {
    }
}
