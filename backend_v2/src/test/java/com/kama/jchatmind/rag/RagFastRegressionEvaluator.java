package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RagFastRegressionEvaluator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagFastRegressionEvaluator() {
    }

    static RagFastRegressionReport evaluate(String manifestPath, String replayPath) throws IOException {
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(manifestPath);
        Map<String, RagFastRegressionReplay> replays = loadReplays(replayPath);
        if (replays.size() != dataset.cases().size()) {
            throw new IllegalStateException("replay 数量与冻结数据集不一致");
        }

        List<RagEvaluationCase> answerable = dataset.cases().stream()
                .filter(item -> !item.shouldAbstain())
                .toList();
        List<RagEvaluationCase> abstentionCases = dataset.cases().stream()
                .filter(RagEvaluationCase::shouldAbstain)
                .toList();
        for (RagEvaluationCase item : dataset.cases()) {
            if (!replays.containsKey(item.caseId())) {
                throw new IllegalStateException("缺少 replay case: " + item.caseId());
            }
        }

        double recallAt5 = answerable.stream()
                .filter(item -> hitAt(replays.get(item.caseId()), item.goldChunkIds(), 5))
                .count() / (double) answerable.size();
        double mrrAt3 = answerable.stream()
                .mapToDouble(item -> reciprocalRank(replays.get(item.caseId()), item.goldChunkIds(), 3))
                .average().orElse(0D);
        double contextPrecisionAt5 = answerable.stream()
                .mapToDouble(item -> RagAsMetrics.contextPrecision(
                        replays.get(item.caseId()).topChunkIds().stream().limit(5).toList(),
                        java.util.Set.copyOf(item.goldChunkIds())
                ))
                .average().orElse(0D);
        double contextRecallAt5 = answerable.stream()
                .mapToDouble(item -> RagAsMetrics.contextRecall(
                        replays.get(item.caseId()).topChunkIds().stream().limit(5).toList(),
                        java.util.Set.copyOf(item.goldChunkIds())
                ))
                .average().orElse(0D);
        double contextPrecisionAt10 = answerable.stream()
                .mapToDouble(item -> RagAsMetrics.contextPrecision(
                        replays.get(item.caseId()).topChunkIds().stream().limit(10).toList(),
                        java.util.Set.copyOf(item.goldChunkIds())
                ))
                .average().orElse(0D);
        double contextRecallAt10 = answerable.stream()
                .mapToDouble(item -> RagAsMetrics.contextRecall(
                        replays.get(item.caseId()).topChunkIds().stream().limit(10).toList(),
                        java.util.Set.copyOf(item.goldChunkIds())
                ))
                .average().orElse(0D);
        double abstentionAccuracy = abstentionCases.stream()
                .filter(item -> replays.get(item.caseId()).abstained())
                .count() / (double) abstentionCases.size();

        return new RagFastRegressionReport(
                dataset.manifest().datasetId(),
                sha256(readResource(manifestPath)),
                "replay",
                dataset.cases().size(),
                answerable.size(),
                abstentionCases.size(),
                recallAt5,
                mrrAt3,
                contextPrecisionAt5,
                contextRecallAt5,
                contextPrecisionAt10,
                contextRecallAt10,
                abstentionAccuracy
        );
    }

    static RagFastRegressionReport evaluateAndWrite(
            String manifestPath,
            String replayPath,
            Path reportPath
    ) throws IOException {
        RagFastRegressionReport report = evaluate(manifestPath, replayPath);
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return report;
    }

    private static Map<String, RagFastRegressionReplay> loadReplays(String replayPath) throws IOException {
        Map<String, RagFastRegressionReplay> replays = new LinkedHashMap<>();
        try (InputStream inputStream = new ClassPathResource(replayPath).getInputStream()) {
            for (String line : new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (StringUtils.hasText(line)) {
                    RagFastRegressionReplay replay = OBJECT_MAPPER.readValue(line, RagFastRegressionReplay.class);
                    if (replays.putIfAbsent(replay.caseId(), replay) != null) {
                        throw new IllegalStateException("replay caseId 重复: " + replay.caseId());
                    }
                }
            }
        }
        return replays;
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean hitAt(RagFastRegressionReplay replay, List<String> goldChunkIds, int k) {
        return replay.topChunkIds().stream().limit(k).anyMatch(goldChunkIds::contains);
    }

    private static double reciprocalRank(RagFastRegressionReplay replay, List<String> goldChunkIds, int k) {
        for (int i = 0; i < Math.min(k, replay.topChunkIds().size()); i++) {
            if (goldChunkIds.contains(replay.topChunkIds().get(i))) {
                return 1D / (i + 1);
            }
        }
        return 0D;
    }
}
