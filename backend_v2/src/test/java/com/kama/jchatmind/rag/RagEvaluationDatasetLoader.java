package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

final class RagEvaluationDatasetLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagEvaluationDatasetLoader() {
    }

    static RagEvaluationDataset load(String manifestPath) throws IOException {
        RagEvaluationDatasetManifest manifest = readJson(manifestPath, RagEvaluationDatasetManifest.class);
        validateManifest(manifest, manifestPath);
        validateCorpus(manifest, manifestPath);

        byte[] caseBytes = readBytes(resolveSiblingPath(manifestPath, manifest.caseFile()));
        if (!sha256(caseBytes).equals(manifest.caseSha256())) {
            throw new IllegalStateException("评测 case 文件校验失败: " + manifest.caseFile());
        }
        List<RagEvaluationCase> cases = parseCases(caseBytes, manifest.datasetId());
        validateCases(cases, manifest.datasetId());
        return new RagEvaluationDataset(manifest, List.copyOf(cases));
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

    private static List<RagEvaluationCase> parseCases(byte[] caseBytes, String datasetId) throws IOException {
        List<RagEvaluationCase> cases = new ArrayList<>();
        for (String line : new String(caseBytes, StandardCharsets.UTF_8).split("\\R")) {
            if (StringUtils.hasText(line)) {
                cases.add(OBJECT_MAPPER.readValue(line, RagEvaluationCase.class));
            }
        }
        return cases;
    }

    private static void validateManifest(RagEvaluationDatasetManifest manifest, String manifestPath) {
        if (!StringUtils.hasText(manifest.datasetId()) || !"1".equals(manifest.schemaVersion())
                || !"frozen".equals(manifest.status()) || !StringUtils.hasText(manifest.caseFile())
                || !StringUtils.hasText(manifest.caseSha256()) || manifest.defaultTopK() <= 0) {
            throw new IllegalStateException("评测 manifest 不符合 schema v1: " + manifestPath);
        }
    }

    static void validateCases(List<RagEvaluationCase> cases, String datasetId) {
        if (cases.isEmpty()) {
            throw new IllegalStateException("冻结评测数据集不能为空: " + datasetId);
        }
        HashSet<String> caseIds = new HashSet<>();
        for (RagEvaluationCase item : cases) {
            if (!datasetId.equals(item.datasetId()) || !StringUtils.hasText(item.caseId())
                    || !StringUtils.hasText(item.query()) || !StringUtils.hasText(item.queryType())
                    || item.kbScope() == null || item.kbScope().isEmpty()
                    || !"approved".equals(item.reviewStatus())) {
                throw new IllegalStateException("评测 case 不符合 schema v1: " + item.caseId());
            }
            if (!caseIds.add(item.caseId())) {
                throw new IllegalStateException("评测 caseId 重复: " + item.caseId());
            }
            if (item.shouldAbstain() && StringUtils.hasText(item.abstentionReason()) && !item.goldChunkIds().isEmpty()) {
                throw new IllegalStateException("拒答 case 不能包含 gold chunk: " + item.caseId());
            }
            if (!item.shouldAbstain() && (item.goldChunkIds() == null || item.goldChunkIds().isEmpty())) {
                throw new IllegalStateException("可回答 case 必须包含 gold chunk: " + item.caseId());
            }
        }
    }

    private static void validateCorpus(RagEvaluationDatasetManifest manifest, String manifestPath) throws IOException {
        if (manifest.corpusFiles() == null || manifest.corpusFiles().isEmpty() || !StringUtils.hasText(manifest.corpusSha256())) {
            throw new IllegalStateException("冻结评测数据集必须声明 corpus 校验信息: " + manifest.datasetId());
        }
        StringBuilder content = new StringBuilder();
        for (String corpusFile : manifest.corpusFiles().stream().sorted(Comparator.naturalOrder()).toList()) {
            content.append(corpusFile.substring(corpusFile.lastIndexOf('/') + 1)).append('\n');
            content.append(new String(readBytes(resolveSiblingPath(manifestPath, corpusFile)), StandardCharsets.UTF_8)).append('\n');
        }
        if (!sha256(content.toString().getBytes(StandardCharsets.UTF_8)).equals(manifest.corpusSha256())) {
            throw new IllegalStateException("评测 corpus 文件校验失败: " + manifest.datasetId());
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
}
