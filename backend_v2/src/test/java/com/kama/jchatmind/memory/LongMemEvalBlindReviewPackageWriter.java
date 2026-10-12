package com.kama.jchatmind.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

final class LongMemEvalBlindReviewPackageWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> FORBIDDEN_REVIEW_FIELDS = Set.of(
            "caseId", "candidateId", "evidenceMessageId", "question", "answer", "gold",
            "questionType", "userId", "sessionId"
    );

    private LongMemEvalBlindReviewPackageWriter() {
    }

    static WrittenArtifacts write(
            Path reviewerPacketPath,
            Path privateMappingPath,
            LongMemEvalBlindReviewPackageBuilder.Artifacts artifacts
    ) throws IOException {
        if (reviewerPacketPath == null || privateMappingPath == null || artifacts == null
                || reviewerPacketPath.toAbsolutePath().normalize()
                .equals(privateMappingPath.toAbsolutePath().normalize())
                || reviewerPacketPath.toAbsolutePath().normalize().getParent()
                .equals(privateMappingPath.toAbsolutePath().normalize().getParent())) {
            throw new IllegalArgumentException("LongMemEval blind review artifacts 必须使用两个独立路径");
        }
        validateArtifacts(artifacts);

        byte[] reviewerBytes = toJson(artifacts.reviewerPacket());
        byte[] privateBytes = toJson(artifacts.privateMapping());
        String reviewerSha256 = sha256(reviewerBytes);
        String privateSha256 = sha256(privateBytes);
        byte[] reviewerSidecar = (reviewerSha256 + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        byte[] privateSidecar = (privateSha256 + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);

        verifyExistingOrAbsent(reviewerPacketPath, reviewerBytes);
        verifyExistingOrAbsent(sidecarPath(reviewerPacketPath), reviewerSidecar);
        verifyExistingOrAbsent(privateMappingPath, privateBytes);
        verifyExistingOrAbsent(sidecarPath(privateMappingPath), privateSidecar);

        writeImmutable(reviewerPacketPath, reviewerBytes);
        writeImmutable(sidecarPath(reviewerPacketPath), reviewerSidecar);
        writeImmutable(privateMappingPath, privateBytes);
        writeImmutable(sidecarPath(privateMappingPath), privateSidecar);
        return new WrittenArtifacts(reviewerPacketPath, privateMappingPath, reviewerSha256, privateSha256);
    }

    private static void validateArtifacts(LongMemEvalBlindReviewPackageBuilder.Artifacts artifacts) {
        JsonNode packet = artifacts.reviewerPacket();
        JsonNode mapping = artifacts.privateMapping();
        if (packet == null || mapping == null || containsForbiddenField(packet)) {
            throw new IllegalArgumentException("LongMemEval reviewer packet 为空或包含 target/gold 字段");
        }
        JsonNode items = packet.path("items");
        JsonNode mappings = mapping.path("items");
        if (!items.isArray() || !mappings.isArray()
                || items.size() != mappings.size()
                || !packet.path("batchId").asText().equals(mapping.path("batchId").asText())
                || !"pending".equals(packet.path("reviewStatus").asText())
                || !"private".equals(mapping.path("visibility").asText())) {
            throw new IllegalArgumentException("LongMemEval blind review artifacts metadata 不一致");
        }

        Set<String> reviewItemIds = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            JsonNode reviewItem = items.get(index);
            JsonNode privateEntry = mappings.get(index);
            String reviewItemId = reviewItem.path("reviewItemId").asText();
            if (reviewItemId.isBlank() || !reviewItemIds.add(reviewItemId)
                    || !reviewItemId.equals(privateEntry.path("reviewItemId").asText())) {
                throw new IllegalArgumentException("LongMemEval blind review item/map IDs 无效");
            }
        }
    }

    private static boolean containsForbiddenField(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (FORBIDDEN_REVIEW_FIELDS.contains(field) || containsForbiddenField(node.path(field))) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsForbiddenField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] toJson(JsonNode node) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LongMemEval blind review JSON 序列化失败", exception);
        }
    }

    private static Path sidecarPath(Path artifactPath) {
        return artifactPath.resolveSibling(artifactPath.getFileName() + ".sha256");
    }

    private static void verifyExistingOrAbsent(Path path, byte[] expectedBytes) throws IOException {
        if (Files.exists(path) && !Arrays.equals(Files.readAllBytes(path), expectedBytes)) {
            throw new IllegalStateException("已有 LongMemEval blind review artifact 内容不同，拒绝覆盖: " + path);
        }
    }

    private static void writeImmutable(Path path, byte[] bytes) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("LongMemEval blind review artifact 必须位于目录中");
        }
        Files.createDirectories(parent);
        if (Files.exists(path)) {
            return;
        }
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    record WrittenArtifacts(
            Path reviewerPacket,
            Path privateMapping,
            String reviewerPacketSha256,
            String privateMappingSha256
    ) {
    }
}
