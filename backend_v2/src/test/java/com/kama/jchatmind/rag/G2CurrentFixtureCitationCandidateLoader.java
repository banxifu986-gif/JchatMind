package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureCitationCandidateLoader {

    private static final String DATASET_ID = "fixture-fast-v2-citation-candidate";
    private static final String PARENT_MANIFEST_PATH =
            "rag-eval/datasets/manifests/fixture-fast-v1.json";
    private static final String CITATION_GOLD_CONTRACT_VERSION = "direct-evidence-groups-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private G2CurrentFixtureCitationCandidateLoader() {
    }

    static CandidateDataset load(String manifestPath) throws IOException {
        byte[] manifestBytes = readBytes(manifestPath);
        JsonNode manifest = OBJECT_MAPPER.readTree(manifestBytes);
        String casePath = resolveSiblingPath(manifestPath, manifest.path("caseFile").asText());
        return load(manifestPath, manifestBytes, readBytes(casePath));
    }

    static CandidateDataset load(String manifestPath, byte[] manifestBytes, byte[] caseBytes) throws IOException {
        JsonNode manifest = OBJECT_MAPPER.readTree(manifestBytes);
        validateManifest(manifest, manifestPath);
        String reviewStatus = manifest.path("reviewStatus").asText();

        RagEvaluationDataset parent = RagEvaluationDatasetLoader.load(PARENT_MANIFEST_PATH);
        validateParent(manifest, parent);
        G2CurrentFixtureRuntimeCorpus parentCorpus =
                G2CurrentFixtureRuntimeCorpusLoader.load(PARENT_MANIFEST_PATH);

        String caseSha256 = sha256(caseBytes);
        if (!caseSha256.equals(manifest.path("caseSha256").asText())) {
            throw new IllegalStateException("fixture citation candidate case SHA-256 不匹配");
        }

        List<CandidateCase> cases = parseCases(
                caseBytes,
                manifest.path("datasetId").asText(),
                parent,
                parentCorpus.chunksByLogicalId().keySet(),
                reviewStatus
        );
        if (cases.size() != parent.cases().size()
                || !cases.stream().map(CandidateCase::parentCaseId).collect(java.util.stream.Collectors.toSet())
                .equals(parent.cases().stream().map(RagEvaluationCase::caseId).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalStateException("fixture citation candidate 必须精确覆盖 parent case 集");
        }

        return new CandidateDataset(
                DATASET_ID,
                parent.manifest().datasetId(),
                manifest.path("status").asText(),
                reviewStatus,
                caseSha256,
                manifest.path("corpusSha256").asText(),
                List.copyOf(cases)
        );
    }

    private static void validateManifest(JsonNode manifest, String manifestPath) {
        if (!DATASET_ID.equals(manifest.path("datasetId").asText())
                || !"2".equals(manifest.path("schemaVersion").asText())
                || !"candidate".equals(manifest.path("status").asText())
                || !Set.of("pending", "approved").contains(manifest.path("reviewStatus").asText())
                || !CITATION_GOLD_CONTRACT_VERSION.equals(manifest.path("citationGoldContractVersion").asText())
                || !StringUtils.hasText(manifest.path("caseFile").asText())
                || !StringUtils.hasText(manifest.path("caseSha256").asText())
                || !StringUtils.hasText(manifest.path("corpusSha256").asText())
                || manifest.path("defaultTopK").asInt() <= 0
                || !manifest.path("corpusFiles").isArray()
                || manifest.path("corpusFiles").isEmpty()) {
            throw new IllegalStateException("fixture citation candidate manifest 不符合 schema v2: " + manifestPath);
        }
    }

    private static void validateParent(JsonNode manifest, RagEvaluationDataset parent) {
        RagEvaluationDatasetManifest parentManifest = parent.manifest();
        if (!parentManifest.datasetId().equals(manifest.path("parentDatasetId").asText())
                || !parentManifest.caseSha256().equals(manifest.path("parentCaseSha256").asText())
                || !parentManifest.corpusSha256().equals(manifest.path("corpusSha256").asText())
                || !parentManifest.corpusFiles().equals(strings(manifest.path("corpusFiles")))
                || !parentManifest.chunkingVersion().equals(manifest.path("chunkingVersion").asText())
                || !parentManifest.embeddingModel().equals(manifest.path("embeddingModel").asText())
                || !parentManifest.embeddingInputVersion().equals(manifest.path("embeddingInputVersion").asText())
                || parentManifest.defaultTopK() != manifest.path("defaultTopK").asInt()) {
            throw new IllegalStateException("fixture citation candidate parent/configuration fingerprint 不匹配");
        }
    }

    private static List<CandidateCase> parseCases(
            byte[] caseBytes,
            String datasetId,
            RagEvaluationDataset parent,
            Set<String> knownLogicalChunkIds,
            String reviewStatus
    ) throws IOException {
        Map<String, RagEvaluationCase> parentById = new HashMap<>();
        parent.cases().forEach(item -> parentById.put(item.caseId(), item));
        List<CandidateCase> cases = new ArrayList<>();
        Set<String> candidateIds = new HashSet<>();
        Set<String> mappedParentIds = new HashSet<>();
        for (String line : new String(caseBytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = OBJECT_MAPPER.readTree(line);
            String caseId = node.path("caseId").asText();
            String parentCaseId = caseId.replaceFirst(
                    "^fixture-fast-v2-citation-candidate-",
                    "fixture-fast-v1-"
            );
            RagEvaluationCase parentCase = parentById.get(parentCaseId);
            List<String> goldChunkIds = strings(node.path("goldChunkIds"));
            List<List<String>> goldCitationGroups = stringGroups(node.path("goldCitationGroups"));
            List<String> sourceDocumentIds = strings(node.path("sourceDocumentIds"));
            boolean shouldAbstain = node.path("shouldAbstain").asBoolean();
            if (!datasetId.equals(node.path("datasetId").asText())
                    || !StringUtils.hasText(caseId)
                    || !candidateIds.add(caseId)
                    || parentCase == null
                    || !mappedParentIds.add(parentCaseId)
                    || shouldAbstain != parentCase.shouldAbstain()
                    || !goldChunkIds.equals(parentCase.goldChunkIds())
                    || !reviewStatus.equals(node.path("reviewStatus").asText())) {
                throw new IllegalStateException("fixture citation candidate case 无效或越过 pending review 状态: " + caseId);
            }
            validateCitationGroups(caseId, shouldAbstain, goldChunkIds, goldCitationGroups,
                    sourceDocumentIds, knownLogicalChunkIds);
            cases.add(new CandidateCase(
                    caseId,
                    parentCaseId,
                    node.path("query").asText(),
                    shouldAbstain,
                    sourceDocumentIds,
                    goldChunkIds,
                    goldCitationGroups,
                    node.path("reviewStatus").asText()
            ));
        }
        if (cases.isEmpty() || candidateIds.size() != cases.size()) {
            throw new IllegalStateException("fixture citation candidate case 为空或重复");
        }
        return List.copyOf(cases);
    }

    private static void validateCitationGroups(
            String caseId,
            boolean shouldAbstain,
            List<String> goldChunkIds,
            List<List<String>> groups,
            List<String> sourceDocumentIds,
            Set<String> knownLogicalChunkIds
    ) {
        if (shouldAbstain) {
            if (!goldChunkIds.isEmpty() || !groups.isEmpty()) {
                throw new IllegalStateException("citation candidate no-answer case 不得声明 gold: " + caseId);
            }
            return;
        }
        if (goldChunkIds.isEmpty() || groups.size() != goldChunkIds.size()) {
            throw new IllegalStateException("citation candidate gold groups 必须逐项覆盖 canonical gold: " + caseId);
        }
        Set<String> groupedIds = new HashSet<>();
        for (int index = 0; index < groups.size(); index++) {
            List<String> group = groups.get(index);
            if (group.isEmpty() || !group.contains(goldChunkIds.get(index))) {
                throw new IllegalStateException("citation candidate gold group 缺少 canonical gold: " + caseId);
            }
            for (String logicalId : group) {
                int delimiter = logicalId.indexOf('#');
                String sourceDocumentId = delimiter < 0 ? "" : logicalId.substring(0, delimiter);
                if (!knownLogicalChunkIds.contains(logicalId)
                        || !sourceDocumentIds.contains(sourceDocumentId)
                        || !groupedIds.add(logicalId)) {
                    throw new IllegalStateException("citation candidate group 含未知/重复/未声明来源 chunk: "
                            + caseId + " -> " + logicalId);
                }
            }
        }
    }

    private static List<List<String>> stringGroups(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalStateException("citation candidate goldCitationGroups 必须为数组");
        }
        List<List<String>> groups = new ArrayList<>();
        for (JsonNode groupNode : node) {
            List<String> group = strings(groupNode);
            if (group.isEmpty() || group.stream().distinct().count() != group.size()) {
                throw new IllegalStateException("citation candidate group 不能为空或包含重复 chunk");
            }
            groups.add(group);
        }
        return List.copyOf(groups);
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static byte[] readBytes(String path) throws IOException {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private static String resolveSiblingPath(String manifestPath, String relativePath) {
        int separator = manifestPath.lastIndexOf('/');
        return manifestPath.substring(0, separator + 1) + relativePath;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    record CandidateDataset(
            String datasetId,
            String parentDatasetId,
            String status,
            String reviewStatus,
            String caseSha256,
            String corpusSha256,
            List<CandidateCase> cases
    ) {
        CandidateDataset {
            cases = List.copyOf(cases);
        }

        boolean releaseEligible() {
            return "frozen".equals(status) && "approved".equals(reviewStatus)
                    && cases.stream().allMatch(item -> "approved".equals(item.reviewStatus()));
        }

        CandidateCase caseById(String caseId) {
            return cases.stream().filter(item -> item.caseId().equals(caseId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("candidate case 不存在: " + caseId));
        }
    }

    record CandidateCase(
            String caseId,
            String parentCaseId,
            String query,
            boolean shouldAbstain,
            List<String> sourceDocumentIds,
            List<String> goldChunkIds,
            List<List<String>> goldCitationGroups,
            String reviewStatus
    ) {
        CandidateCase {
            sourceDocumentIds = List.copyOf(sourceDocumentIds);
            goldChunkIds = List.copyOf(goldChunkIds);
            goldCitationGroups = goldCitationGroups.stream().map(List::copyOf).toList();
        }
    }
}
