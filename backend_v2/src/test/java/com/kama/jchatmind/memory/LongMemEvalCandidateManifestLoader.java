package com.kama.jchatmind.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

final class LongMemEvalCandidateManifestLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LongMemEvalCandidateManifestLoader() {
    }

    static VerifiedManifest load(Path manifestPath, JsonNode sourcePin) throws IOException {
        if (manifestPath == null || !Files.isRegularFile(manifestPath) || sourcePin == null) {
            throw new IllegalArgumentException("LongMemEval candidate manifest 或 source pin 缺失");
        }
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        String manifestSha256 = sha256(manifestBytes);
        Path sidecarPath = manifestPath.resolveSibling(manifestPath.getFileName() + ".sha256");
        if (!Files.isRegularFile(sidecarPath)
                || !manifestSha256.equals(Files.readString(sidecarPath).trim())) {
            throw new IllegalStateException("LongMemEval candidate manifest sidecar SHA-256 不匹配");
        }

        JsonNode manifest = OBJECT_MAPPER.readTree(manifestBytes);
        validateManifest(manifest, sourcePin);
        String caseSha256 = sha256(writeJson(manifest.path("cases")));
        if (!caseSha256.equals(manifest.path("caseSha256").asText())) {
            throw new IllegalStateException("LongMemEval candidate manifest caseSha256 不匹配");
        }
        validateCases(manifest.path("cases"), manifest.path("categoryPlan"));
        return new VerifiedManifest(manifest.deepCopy(), manifestSha256, caseSha256);
    }

    private static void validateManifest(JsonNode manifest, JsonNode sourcePin) {
        JsonNode projectSubset = sourcePin.path("projectSubset");
        JsonNode source = sourcePin.path("source");
        JsonNode evaluator = sourcePin.path("officialEvaluator");
        if (!manifest.isObject()
                || manifest.path("schemaVersion").asInt() != 1
                || !"longmemeval-30-v1".equals(manifest.path("datasetId").asText())
                || !"candidate".equals(manifest.path("status").asText())
                || manifest.path("releaseEligible").asBoolean(true)
                || !"pending".equals(manifest.path("reviewStatus").asText())
                || manifest.path("answerEvaluationExecuted").asBoolean(true)
                || manifest.path("caseCount").asInt(-1) != projectSubset.path("caseCount").asInt(-2)
                || manifest.path("caseCount").asInt(-1) != manifest.path("cases").size()
                || manifest.path("selectionSeed").asLong(-1) != projectSubset.path("selectionSeed").asLong(-2)
                || !manifest.path("selectionAlgorithm").asText()
                .equals(projectSubset.path("selectionAlgorithm").asText())
                || !manifest.path("sourceDatasetId").asText().equals(sourcePin.path("datasetId").asText())
                || !manifest.path("sourceFileName").asText().equals(source.path("fileName").asText())
                || !manifest.path("sourceRevision").asText().equals(source.path("revision").asText())
                || !manifest.path("sourceSha256").asText().equals(source.path("sha256").asText())
                || !manifest.path("source").equals(source)
                || !manifest.path("officialEvaluator").equals(evaluator)
                || !manifest.path("categoryPlan").equals(projectSubset.path("categories"))) {
            throw new IllegalStateException("LongMemEval candidate manifest source pin 或 pending metadata 漂移");
        }
    }

    private static void validateCases(JsonNode cases, JsonNode categoryPlan) {
        Map<String, Integer> expectedCounts = new HashMap<>();
        for (JsonNode category : categoryPlan) {
            expectedCounts.put(category.path("id").asText(), category.path("count").asInt());
        }
        Map<String, Integer> actualCounts = new HashMap<>();
        Set<String> caseIds = new HashSet<>();
        Set<Integer> sourceIndexes = new HashSet<>();
        for (JsonNode candidateCase : cases) {
            String caseId = candidateCase.path("caseId").asText();
            String categoryId = candidateCase.path("categoryId").asText();
            JsonNode sourceRecord = candidateCase.path("sourceRecord");
            if (caseId.isBlank() || !caseIds.add(caseId)
                    || !expectedCounts.containsKey(categoryId)
                    || !"pending".equals(candidateCase.path("reviewStatus").asText())
                    || !caseId.equals(sourceRecord.path("question_id").asText())
                    || !sourceIndexes.add(candidateCase.path("sourceIndex").asInt(-1))
                    || !candidateCase.path("sourceRecordSha256").asText()
                    .equals(sha256Unchecked(writeJson(sourceRecord)))) {
                throw new IllegalStateException("LongMemEval candidate manifest case source 或 hash 无效: " + caseId);
            }
            validateSessionOccurrences(candidateCase, sourceRecord);
            actualCounts.merge(categoryId, 1, Integer::sum);
        }
        if (!actualCounts.equals(expectedCounts)) {
            throw new IllegalStateException("LongMemEval candidate manifest category count 不匹配");
        }
    }

    private static void validateSessionOccurrences(JsonNode candidateCase, JsonNode sourceRecord) {
        JsonNode occurrences = candidateCase.path("sessionOccurrences");
        JsonNode sessionIds = sourceRecord.path("haystack_session_ids");
        JsonNode dates = sourceRecord.path("haystack_dates");
        JsonNode sessions = sourceRecord.path("haystack_sessions");
        String caseId = candidateCase.path("caseId").asText();
        if (!occurrences.isArray() || !sessionIds.isArray() || !dates.isArray() || !sessions.isArray()
                || occurrences.size() != sessionIds.size()
                || occurrences.size() != dates.size()
                || occurrences.size() != sessions.size()) {
            throw new IllegalStateException("LongMemEval candidate session occurrence 数量不匹配: " + caseId);
        }
        for (int index = 0; index < occurrences.size(); index++) {
            JsonNode occurrence = occurrences.get(index);
            if (occurrence.path("occurrenceIndex").asInt(-1) != index
                    || !caseId.concat("#").concat(Integer.toString(index))
                    .equals(occurrence.path("occurrenceKey").asText())
                    || !sessionIds.get(index).asText().equals(occurrence.path("sourceSessionId").asText())
                    || !dates.get(index).asText().equals(occurrence.path("date").asText())) {
                throw new IllegalStateException("LongMemEval candidate session occurrence mapping 无效: " + caseId);
            }
        }
    }

    private static byte[] writeJson(JsonNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LongMemEval candidate JSON 序列化失败", exception);
        }
    }

    private static String sha256Unchecked(byte[] bytes) {
        return sha256(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    record VerifiedManifest(JsonNode manifest, String manifestSha256, String caseSha256) {
    }
}
