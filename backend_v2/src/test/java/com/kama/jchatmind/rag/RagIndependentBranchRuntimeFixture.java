package com.kama.jchatmind.rag;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class RagIndependentBranchRuntimeFixture {

    private static final String DATASET_ID = "g2-pre-bm25-v1";
    private static final String ARCHITECTURE_RESOURCE = "rag-eval/datasets/corpus/g2-pre-bm25-v1/g2-architecture.md";
    private static final String PDF_RESOURCE = "rag-eval/datasets/corpus/g2-pre-bm25-v1/g2-architecture-pdf-pages.md";

    private RagIndependentBranchRuntimeFixture() {
    }

    static Fixture load(RagEvaluationDataset dataset) throws IOException {
        if (dataset == null || !DATASET_ID.equals(dataset.manifest().datasetId())) {
            throw new IllegalArgumentException("独立三路运行时 fixture 只支持冻结 g2-pre-bm25-v1 数据集");
        }
        Map<String, String> architecture = sections(ARCHITECTURE_RESOURCE);
        Map<String, String> pdfPages = sections(PDF_RESOURCE);
        List<Candidate> candidates = new ArrayList<>();
        addArchitectureCandidate(candidates, architecture, "PostgreSQL 原生 BM25 迁移");
        addArchitectureCandidate(candidates, architecture, "JVM 词法候选边界");
        addArchitectureCandidate(candidates, architecture, "HARD 会话上下文");
        addArchitectureCandidate(candidates, architecture, "受控 Router 与拒答");
        addArchitectureCandidate(candidates, architecture, "API 路径与标题通道");
        addPdfCandidate(candidates, pdfPages, "第 1 页", 1);
        addPdfCandidate(candidates, pdfPages, "第 2 页", 2);

        Map<String, String> logicalChunkIdByRuntimeUuid = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (logicalChunkIdByRuntimeUuid.putIfAbsent(candidate.runtimeChunkUuid(), candidate.logicalChunkId()) != null) {
                throw new IllegalStateException("独立三路运行时 fixture 出现重复 UUID");
            }
        }
        return new Fixture(
                List.copyOf(candidates),
                Map.copyOf(logicalChunkIdByRuntimeUuid),
                sha256(candidates.stream().map(Candidate::identity).toList())
        );
    }

    private static void addArchitectureCandidate(
            List<Candidate> candidates,
            Map<String, String> sections,
            String title
    ) {
        String logicalChunkId = "g2-architecture#" + title + "#0";
        candidates.add(candidate(
                logicalChunkId,
                "g2-architecture",
                "g2-architecture.md",
                "md",
                "G2 RAG Baseline Architecture > " + title,
                title,
                sections.get(title),
                null
        ));
    }

    private static void addPdfCandidate(
            List<Candidate> candidates,
            Map<String, String> sections,
            String title,
            int pageNumber
    ) {
        String logicalChunkId = "architecture.pdf#" + title + "#0";
        candidates.add(candidate(
                logicalChunkId,
                "architecture.pdf",
                "architecture.pdf",
                "pdf",
                "architecture.pdf > " + title,
                title,
                sections.get(title),
                pageNumber
        ));
    }

    private static Candidate candidate(
            String logicalChunkId,
            String documentId,
            String sourceName,
            String sourceType,
            String contentPath,
            String title,
            String body,
            Integer pageNumber
    ) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("独立三路运行时 fixture 缺少冻结标题正文: " + title);
        }
        return new Candidate(
                logicalChunkId,
                UUID.nameUUIDFromBytes(("g2-three-branch:" + logicalChunkId).getBytes(StandardCharsets.UTF_8)).toString(),
                UUID.nameUUIDFromBytes(("g2-three-branch-document:" + documentId).getBytes(StandardCharsets.UTF_8)).toString(),
                documentId,
                sourceName,
                sourceType,
                contentPath,
                title,
                title + "\n" + body.trim(),
                pageNumber
        );
    }

    private static Map<String, String> sections(String resourcePath) throws IOException {
        String content;
        try (var input = new ClassPathResource(resourcePath).getInputStream()) {
            content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> sections = new LinkedHashMap<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\\R")) {
            if (line.startsWith("## ")) {
                if (title != null) {
                    sections.put(title, body.toString());
                }
                title = line.substring(3).trim();
                body = new StringBuilder();
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        if (title != null) {
            sections.put(title, body.toString());
        }
        return sections;
    }

    private static String sha256(List<String> values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    record Fixture(
            List<Candidate> candidates,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            String fixtureSha256
    ) {
    }

    record Candidate(
            String logicalChunkId,
            String runtimeChunkUuid,
            String runtimeDocumentUuid,
            String documentId,
            String sourceName,
            String sourceType,
            String contentPath,
            String title,
            String content,
            Integer pageNumber
    ) {
        String identity() {
            return String.join("|", logicalChunkId, runtimeChunkUuid, runtimeDocumentUuid, documentId,
                    sourceName, sourceType, contentPath, title, content, pageNumber == null ? "" : pageNumber.toString());
        }
    }
}
