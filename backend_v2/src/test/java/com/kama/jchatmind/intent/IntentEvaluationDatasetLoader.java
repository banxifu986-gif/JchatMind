package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class IntentEvaluationDatasetLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private IntentEvaluationDatasetLoader() {
    }

    static IntentEvaluationDataset load(String manifestPath) throws IOException {
        IntentEvaluationDatasetManifest manifest = readJson(manifestPath, IntentEvaluationDatasetManifest.class);
        validateManifest(manifest, manifestPath);

        List<IntentEvaluationCase> cases;
        String effectiveCaseSha256;
        if ("4".equals(manifest.schemaVersion())) {
            byte[] seedBytes = readBytes(resolveSiblingPath(manifestPath, manifest.caseFile()));
            if (!sha256(seedBytes).equals(manifest.caseSha256())) {
                throw new IllegalStateException("candidate intent 数据集 seed SHA-256 校验失败: "
                        + manifest.caseFile());
            }
            List<IntentEvaluationCase> seeds = parseCases(seedBytes);
            validateCases(seeds, manifest.datasetId(), "candidate");
            cases = IntentEvaluationCandidateExpander.expand(seeds, manifest.datasetId());
            validateCases(cases, manifest.datasetId(), manifest.status());
            validateCandidateCounts(manifest, cases);
            effectiveCaseSha256 = sha256(serializeCases(cases));
        } else {
            byte[] caseBytes = readBytes(resolveSiblingPath(manifestPath, manifest.caseFile()));
            if (!sha256(caseBytes).equals(manifest.caseSha256())) {
                throw new IllegalStateException("intent 评测 case 文件校验失败: " + manifest.caseFile());
            }

            cases = parseCases(caseBytes);
            validateCases(cases, manifest.datasetId(), manifest.status());
            effectiveCaseSha256 = manifest.caseSha256();
        }
        return new IntentEvaluationDataset(manifest, List.copyOf(cases), effectiveCaseSha256);
    }

    static void validateCases(List<IntentEvaluationCase> cases, String datasetId) {
        validateCases(cases, datasetId, "frozen");
    }

    private static void validateCases(List<IntentEvaluationCase> cases, String datasetId, String datasetStatus) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalStateException("intent 评测数据集不能为空: " + datasetId);
        }
        HashSet<String> caseIds = new HashSet<>();
        for (IntentEvaluationCase item : cases) {
            if (item == null
                    || !datasetId.equals(item.datasetId())
                    || !StringUtils.hasText(item.caseId())
                    || !StringUtils.hasText(item.split())
                    || item.query() == null
                    || (item.skipReason() != null
                    && (!StringUtils.hasText(item.skipReason()) || item.skipReason().length() > 128))
                    || item.expectedBusinessIntent() == null
                    || item.expectedRoute() == null
                    || item.expectedRiskLevel() == null
                    || item.expectedCapabilities() == null
                    || !validReviewStatus(datasetStatus, item.reviewStatus())) {
                throw new IllegalStateException("intent 评测 case contract invalid for status " + datasetStatus + ": "
                        + (item == null ? "null" : item.caseId()));
            }
            if (!caseIds.add(item.caseId())) {
                throw new IllegalStateException("intent 评测 caseId 重复: " + item.caseId());
            }
            if (item.shouldAbstain() && item.expectedBusinessIntent() != BusinessIntent.ABSTAIN) {
                throw new IllegalStateException("拒答标记与业务意图不一致: " + item.caseId());
            }
            if (item.shouldClarify() && item.expectedBusinessIntent() != BusinessIntent.CLARIFY
                    && item.expectedBusinessIntent() != BusinessIntent.COMPOSITE) {
                throw new IllegalStateException("澄清标记与业务意图不一致: " + item.caseId());
            }
            if (item.shouldAbstain() && !item.expectedCapabilities().isEmpty()) {
                throw new IllegalStateException("拒答 case 不能包含候选能力: " + item.caseId());
            }
        }
    }

    private static boolean validReviewStatus(String datasetStatus, String reviewStatus) {
        return "frozen".equals(datasetStatus)
                ? "approved".equals(reviewStatus)
                : "candidate".equals(datasetStatus) && "pending_review".equals(reviewStatus);
    }

    private static <T> T readJson(String path, Class<T> type) throws IOException {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return OBJECT_MAPPER.readValue(inputStream, type);
        }
    }

    private static byte[] readBytes(String path) throws IOException {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private static List<IntentEvaluationCase> parseCases(byte[] caseBytes) throws IOException {
        List<IntentEvaluationCase> cases = new ArrayList<>();
        for (String line : new String(caseBytes, StandardCharsets.UTF_8).split("\\R")) {
            if (StringUtils.hasText(line)) {
                cases.add(OBJECT_MAPPER.readValue(line, IntentEvaluationCase.class));
            }
        }
        return cases;
    }

    static void validateManifest(IntentEvaluationDatasetManifest manifest, String manifestPath) {
        if (manifest == null || !StringUtils.hasText(manifest.datasetId())
                || manifest.caseSha256() == null || !manifest.caseSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("intent 评测 manifest missing required fields: " + manifestPath);
        }
        if ("1".equals(manifest.schemaVersion())) {
            if (!"frozen".equals(manifest.status()) || !StringUtils.hasText(manifest.caseFile())) {
                throw new IllegalStateException("intent frozen manifest 状态不合法: " + manifestPath);
            }
            return;
        }
        if (!"4".equals(manifest.schemaVersion())
                || !"candidate".equals(manifest.status())
                || manifest.fixtureOnly()
                || !StringUtils.hasText(manifest.caseFile())
                || !StringUtils.hasText(manifest.sourceDatasetId())
                || !IntentEvaluationCandidateExpander.GENERATOR_VERSION.equals(manifest.generatorVersion())
                || manifest.expectedCaseCount() == null
                || manifest.splitCounts() == null) {
            throw new IllegalStateException("intent candidate manifest 不符合 schema v4: " + manifestPath);
        }
    }

    private static void validateCandidateCounts(
            IntentEvaluationDatasetManifest manifest,
            List<IntentEvaluationCase> cases
    ) {
        if (cases.size() != manifest.expectedCaseCount()) {
            throw new IllegalStateException("intent candidate case 数量不符合 manifest: " + manifest.datasetId());
        }
        Map<String, Integer> actualCounts = new TreeMap<>();
        for (IntentEvaluationCase item : cases) {
            actualCounts.merge(item.split(), 1, Integer::sum);
        }
        if (!new TreeMap<>(manifest.splitCounts()).equals(actualCounts)) {
            throw new IllegalStateException("intent candidate split 分布不符合 manifest: " + manifest.datasetId());
        }
    }

    private static byte[] serializeCases(List<IntentEvaluationCase> cases) throws IOException {
        StringBuilder content = new StringBuilder();
        for (IntentEvaluationCase item : cases) {
            content.append(OBJECT_MAPPER.writeValueAsString(item)).append('\n');
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
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
}
