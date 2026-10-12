package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class IntentEvaluationCandidateExporter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private IntentEvaluationCandidateExporter() {
    }

    static IntentEvaluationCandidateExport write(Path output, IntentEvaluationDataset dataset) throws IOException {
        if (output == null || dataset == null) {
            throw new IllegalArgumentException("candidate export output and dataset are required");
        }
        if (!"candidate".equals(dataset.manifest().status()) || dataset.manifest().fixtureOnly()) {
            throw new IllegalArgumentException("only non-fixture candidate datasets can be exported for review");
        }

        StringBuilder jsonl = new StringBuilder();
        for (IntentEvaluationCase item : dataset.cases()) {
            jsonl.append(OBJECT_MAPPER.writeValueAsString(item)).append('\n');
        }

        byte[] caseBytes = jsonl.toString().getBytes(StandardCharsets.UTF_8);
        String caseHash = sha256(caseBytes);
        Path manifestPath = output.resolveSibling(output.getFileName() + ".manifest.json");
        ExportManifest manifest = new ExportManifest(
                "1",
                dataset.manifest().datasetId(),
                dataset.manifest().status(),
                dataset.manifest().caseSha256(),
                dataset.effectiveCaseSha256(),
                dataset.manifest().generatorVersion(),
                output.getFileName().toString(),
                caseHash,
                dataset.cases().size(),
                new TreeMap<>(dataset.manifest().splitCounts()),
                "pending_review"
        );
        byte[] manifestBytes = (OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n")
                .getBytes(StandardCharsets.UTF_8);

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writeOrVerify(output, caseBytes);
        writeOrVerify(manifestPath, manifestBytes);
        return new IntentEvaluationCandidateExport(output, manifestPath, dataset.cases().size(), caseHash);
    }

    private static void writeOrVerify(Path path, byte[] expectedBytes) throws IOException {
        try {
            Files.write(path, expectedBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            if (!MessageDigest.isEqual(Files.readAllBytes(path), expectedBytes)) {
                throw new IOException("candidate review artifact already exists with different content: " + path, e);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ExportManifest(
            String exportSchemaVersion,
            String datasetId,
            String status,
            String datasetSourceSha256,
            String datasetCaseSha256,
            String generatorVersion,
            String caseFile,
            String caseSha256,
            int caseCount,
            Map<String, Integer> splitCounts,
            String reviewStatus
    ) {
    }
}
