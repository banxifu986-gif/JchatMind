package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class RagRegressionCandidateChunkUuidResolver {

    private final ObjectMapper objectMapper = new ObjectMapper();

    List<RagRegressionCandidateChunkUuidMapping.Item> resolve(
            Map<String, RagRegressionCandidateSourceAnchor> anchors,
            Map<String, String> sourceNames,
            List<RagRetrievalResult> databaseCandidates
    ) {
        return anchors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> sourceNames.containsKey(entry.getValue().sourceDocumentLogicalId()))
                .map(entry -> resolveOne(entry.getKey(), entry.getValue(), sourceNames, databaseCandidates))
                .toList();
    }

    private RagRegressionCandidateChunkUuidMapping.Item resolveOne(
            String logicalChunkId,
            RagRegressionCandidateSourceAnchor anchor,
            Map<String, String> sourceNames,
            List<RagRetrievalResult> databaseCandidates
    ) {
        String expectedSourceName = sourceNames.get(anchor.sourceDocumentLogicalId());
        List<String> matches = databaseCandidates.stream()
                .filter(candidate -> expectedSourceName != null && expectedSourceName.equals(metadataText(candidate, "sourceName")))
                .filter(candidate -> anchor.sourceSectionAnchor().equals(retrievableTitle(candidate)))
                .map(RagRetrievalResult::getChunkId)
                .filter(StringUtils::hasText)
                .sorted(Comparator.naturalOrder())
                .toList();
        String status = matches.isEmpty() ? "unmapped" : matches.size() == 1 ? "mapped" : "ambiguous";
        return new RagRegressionCandidateChunkUuidMapping.Item(
                logicalChunkId,
                anchor.sourceDocumentLogicalId(),
                anchor.sourceSectionAnchor(),
                matches,
                status
        );
    }

    private String retrievableTitle(RagRetrievalResult candidate) {
        String title = metadataText(candidate, "retrievableTitle");
        return StringUtils.hasText(title) ? title : metadataText(candidate, "title");
    }

    private String metadataText(RagRetrievalResult candidate, String fieldName) {
        if (!StringUtils.hasText(candidate.getMetadata())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(candidate.getMetadata()).get(fieldName);
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
