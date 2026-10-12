package com.kama.jchatmind.memory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LongMemEval30CandidateFreezer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LongMemEval30CandidateFreezer() {
    }

    static void writeCandidateManifest(
            JsonNode pin,
            Path sourcePath,
            Path manifestPath
    ) throws IOException {
        if (pin == null || manifestPath == null) {
            throw new IllegalArgumentException("LongMemEval source pin 或 candidate manifest 路径缺失");
        }
        JsonNode sourcePin = pin.path("source");
        JsonNode evaluatorPin = pin.path("officialEvaluator");
        verifyPinnedFile(
                Path.of(evaluatorPin.path("localPath").asText()),
                evaluatorPin.path("sizeBytes").asLong(-1),
                evaluatorPin.path("sha256").asText(),
                "official evaluator"
        );

        LongMemEvalOfficialDatasetLoader.SourceSummary sourceSummary =
                LongMemEvalOfficialDatasetLoader.validateSource(sourcePath, sourcePin);
        List<LongMemEval30CaseSelector.SelectedCaseReference> selected =
                LongMemEval30CaseSelector.select(
                        pin.path("projectSubset"), sourceSummary.caseReferences()
                );
        Map<String, JsonNode> sourceRecords = loadSelectedRecords(
                sourcePath,
                sourcePin.path("sha256").asText(),
                selected
        );
        ObjectNode manifest = buildManifest(pin, selected, sourceRecords);
        byte[] manifestBytes = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        String manifestSha256 = sha256(manifestBytes);
        writeImmutable(manifestPath, manifestBytes);
        writeImmutable(
                manifestPath.resolveSibling(manifestPath.getFileName() + ".sha256"),
                (manifestSha256 + System.lineSeparator())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private static Map<String, JsonNode> loadSelectedRecords(
            Path sourcePath,
            String expectedSha256,
            List<LongMemEval30CaseSelector.SelectedCaseReference> selected
    ) throws IOException {
        Set<String> selectedIds = new HashSet<>();
        for (LongMemEval30CaseSelector.SelectedCaseReference item : selected) {
            selectedIds.add(item.sourceReference().questionId());
        }
        Map<String, JsonNode> recordsById = new HashMap<>();
        MessageDigest digest = newSha256();
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
                JsonNode record = OBJECT_MAPPER.readTree(parser);
                String questionId = record.path("question_id").asText();
                if (selectedIds.contains(questionId)
                        && recordsById.putIfAbsent(questionId, record) != null) {
                    throw new IllegalStateException("LongMemEval selected question_id 重复: " + questionId);
                }
            }
            if (parser.nextToken() != null) {
                throw new IllegalStateException("LongMemEval source JSON array 后存在额外内容");
            }
        }
        if (!expectedSha256.equals(HexFormat.of().formatHex(digest.digest()))
                || recordsById.size() != selectedIds.size()) {
            throw new IllegalStateException("LongMemEval source 在候选抽取期间发生漂移或 selected case 缺失");
        }
        return recordsById;
    }

    private static ObjectNode buildManifest(
            JsonNode pin,
            List<LongMemEval30CaseSelector.SelectedCaseReference> selected,
            Map<String, JsonNode> sourceRecords
    ) throws IOException {
        JsonNode sourcePin = pin.path("source");
        JsonNode evaluatorPin = pin.path("officialEvaluator");
        JsonNode subsetPin = pin.path("projectSubset");
        ObjectNode manifest = OBJECT_MAPPER.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("datasetId", subsetPin.path("datasetId").asText());
        manifest.put("status", "candidate");
        manifest.put("releaseEligible", false);
        manifest.put("reviewStatus", "pending");
        manifest.put("answerEvaluationExecuted", false);
        manifest.put("caseCount", selected.size());
        manifest.put("selectionSeed", subsetPin.path("selectionSeed").asLong());
        manifest.put("selectionAlgorithm", subsetPin.path("selectionAlgorithm").asText());
        manifest.put("sourceDatasetId", pin.path("datasetId").asText());
        manifest.set("source", sourcePin.deepCopy());
        manifest.put("sourceFileName", sourcePin.path("fileName").asText());
        manifest.put("sourceRevision", sourcePin.path("revision").asText());
        manifest.put("sourceSha256", sourcePin.path("sha256").asText());
        manifest.set("categoryPlan", subsetPin.path("categories").deepCopy());
        manifest.set("officialEvaluator", evaluatorPin.deepCopy());

        ArrayNode cases = manifest.putArray("cases");
        for (LongMemEval30CaseSelector.SelectedCaseReference selectedCase : selected) {
            String questionId = selectedCase.sourceReference().questionId();
            JsonNode sourceRecord = sourceRecords.get(questionId);
            if (sourceRecord == null) {
                throw new IllegalStateException("LongMemEval selected sourceRecord 缺失: " + questionId);
            }
            ObjectNode caseNode = OBJECT_MAPPER.createObjectNode();
            caseNode.put("caseId", questionId);
            caseNode.put("categoryId", selectedCase.categoryId());
            caseNode.put("questionType", selectedCase.sourceReference().questionType());
            caseNode.put("sourceIndex", selectedCase.sourceReference().sourceIndex());
            caseNode.put("selectionRank", selectedCase.selectionRank());
            caseNode.put("reviewStatus", "pending");
            caseNode.put("sourceRecordSha256", sha256(OBJECT_MAPPER.writeValueAsBytes(sourceRecord)));
            caseNode.set("sourceRecord", sourceRecord);
            caseNode.set("sessionOccurrences", sessionOccurrences(questionId, sourceRecord));
            caseNode.set("evidenceMessageRefs", evidenceMessageRefs(sourceRecord));
            cases.add(caseNode);
        }
        manifest.put("caseSha256", sha256(OBJECT_MAPPER.writeValueAsBytes(cases)));
        return manifest;
    }

    private static ArrayNode sessionOccurrences(String questionId, JsonNode sourceRecord) {
        JsonNode sessionIds = sourceRecord.path("haystack_session_ids");
        JsonNode dates = sourceRecord.path("haystack_dates");
        if (!sessionIds.isArray() || !dates.isArray() || sessionIds.size() != dates.size()) {
            throw new IllegalStateException("LongMemEval source session occurrence arrays 不一致: " + questionId);
        }
        ArrayNode occurrences = OBJECT_MAPPER.createArrayNode();
        for (int index = 0; index < sessionIds.size(); index++) {
            ObjectNode occurrence = occurrences.addObject();
            occurrence.put("occurrenceIndex", index);
            occurrence.put("occurrenceKey", questionId + "#" + index);
            occurrence.put("sourceSessionId", sessionIds.get(index).asText());
            occurrence.put("date", dates.get(index).asText());
        }
        return occurrences;
    }

    private static ArrayNode evidenceMessageRefs(JsonNode sourceRecord) {
        JsonNode sessions = sourceRecord.path("haystack_sessions");
        JsonNode sessionIds = sourceRecord.path("haystack_session_ids");
        ArrayNode refs = OBJECT_MAPPER.createArrayNode();
        for (int sessionIndex = 0; sessionIndex < sessions.size(); sessionIndex++) {
            JsonNode turns = sessions.get(sessionIndex);
            for (int messageIndex = 0; messageIndex < turns.size(); messageIndex++) {
                JsonNode turn = turns.get(messageIndex);
                if (turn.path("has_answer").asBoolean(false)) {
                    ObjectNode ref = refs.addObject();
                    ref.put("occurrenceIndex", sessionIndex);
                    ref.put("sourceSessionId", sessionIds.get(sessionIndex).asText());
                    ref.put("messageIndex", messageIndex);
                }
            }
        }
        return refs;
    }

    private static void verifyPinnedFile(Path path, long expectedSize, String expectedSha256, String label)
            throws IOException {
        if (path == null || !Files.isRegularFile(path) || Files.size(path) != expectedSize
                || !expectedSha256.matches("[0-9a-f]{64}")
                || !expectedSha256.equals(sha256(path))) {
            throw new IllegalStateException("LongMemEval " + label + " file 与 pin 不匹配");
        }
    }

    private static void writeImmutable(Path path, byte[] expectedBytes) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("LongMemEval candidate output 必须位于目录中");
        }
        Files.createDirectories(parent);
        if (Files.exists(path)) {
            if (!Arrays.equals(Files.readAllBytes(path), expectedBytes)) {
                throw new IllegalStateException("已有 LongMemEval candidate artifact 与重新生成内容不同，拒绝覆盖: "
                        + path);
            }
            return;
        }
        Files.write(path, expectedBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = newSha256();
        try (DigestInputStream inputStream = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (inputStream.read(buffer) != -1) {
                // DigestInputStream updates the digest while the source is consumed.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] values) {
        return HexFormat.of().formatHex(newSha256().digest(values));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

}
