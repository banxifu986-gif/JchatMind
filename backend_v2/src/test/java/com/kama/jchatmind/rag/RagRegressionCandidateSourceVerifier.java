package com.kama.jchatmind.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

final class RagRegressionCandidateSourceVerifier {

    RagRegressionCandidateSourceReport verify(
            RagRegressionCandidateDataset dataset,
            Map<String, Path> sourcePaths,
            Map<String, RagRegressionCandidateSourceAnchor> sourceAnchors
    ) throws IOException {
        List<RagRegressionCandidateSourceReport.Item> items = new ArrayList<>();
        for (RagRegressionCandidateCase item : dataset.cases()) {
            Set<String> logicalChunkIds = new LinkedHashSet<>();
            logicalChunkIds.add(item.logicalChunkId());
            logicalChunkIds.addAll(item.additionalGoldLogicalChunkIds());
            for (String logicalChunkId : logicalChunkIds) {
                items.add(verifyChunk(item.caseId(), logicalChunkId, item, sourcePaths, sourceAnchors));
            }
        }
        int verified = (int) items.stream().filter(item -> "verified".equals(item.status())).count();
        return new RagRegressionCandidateSourceReport(
                dataset.datasetId(), items.size(), verified, items.size() - verified,
                "not_attempted", List.copyOf(items)
        );
    }

    private RagRegressionCandidateSourceReport.Item verifyChunk(
            String caseId,
            String logicalChunkId,
            RagRegressionCandidateCase item,
            Map<String, Path> sourcePaths,
            Map<String, RagRegressionCandidateSourceAnchor> sourceAnchors
    ) throws IOException {
            RagRegressionCandidateSourceAnchor sourceAnchor = sourceAnchors.get(logicalChunkId);
            Path sourcePath = sourceAnchor == null ? null : sourcePaths.get(sourceAnchor.sourceDocumentLogicalId());
            boolean isPrimaryChunk = item.logicalChunkId().equals(logicalChunkId);
            boolean hashMatches = sourcePath != null && Files.isRegularFile(sourcePath)
                    && sourceAnchor.sourceDocumentSha256().equals(sha256(sourcePath))
                    && (!isPrimaryChunk || (item.sourceDocumentLogicalId().equals(sourceAnchor.sourceDocumentLogicalId())
                    && item.sourceDocumentSha256().equals(sourceAnchor.sourceDocumentSha256())));
            String sourceSectionAnchor = sourceAnchor == null ? null : sourceAnchor.sourceSectionAnchor();
            boolean anchorMatches = sourcePath != null && Files.isRegularFile(sourcePath)
                    && sourceSectionAnchor != null && headings(sourcePath).contains(sourceSectionAnchor);
            String status = hashMatches && anchorMatches ? "verified" : "failed";
            return new RagRegressionCandidateSourceReport.Item(
                    caseId, sourceAnchor == null ? null : sourceAnchor.sourceDocumentLogicalId(), logicalChunkId, sourceSectionAnchor,
                    hashMatches, anchorMatches, status
            );
    }

    static String sha256(Path sourcePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(sourcePath)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private Set<String> headings(Path sourcePath) throws IOException {
        return Files.readAllLines(sourcePath).stream()
                .filter(line -> line.matches("^#{1,6}\\s+.+"))
                .map(line -> line.replaceFirst("^#{1,6}\\s+", "").trim())
                .collect(Collectors.toUnmodifiableSet());
    }
}
