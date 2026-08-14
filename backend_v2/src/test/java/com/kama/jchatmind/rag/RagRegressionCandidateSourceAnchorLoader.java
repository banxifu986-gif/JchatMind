package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class RagRegressionCandidateSourceAnchorLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RagRegressionCandidateSourceAnchorLoader() {
    }

    static Map<String, RagRegressionCandidateSourceAnchor> load(
            String resourcePath,
            RagRegressionCandidateDataset dataset
    ) throws IOException {
        try (InputStream inputStream = new ClassPathResource(resourcePath).getInputStream()) {
            Map<String, RagRegressionCandidateSourceAnchor> anchors = OBJECT_MAPPER.readValue(inputStream, new TypeReference<>() {
            });
            validate(anchors, dataset);
            return Map.copyOf(anchors);
        }
    }

    private static void validate(
            Map<String, RagRegressionCandidateSourceAnchor> anchors,
            RagRegressionCandidateDataset dataset
    ) {
        Set<String> logicalChunkIds = new LinkedHashSet<>();
        for (RagRegressionCandidateCase item : dataset.cases()) {
            logicalChunkIds.add(item.logicalChunkId());
            logicalChunkIds.addAll(item.additionalGoldLogicalChunkIds());
        }
        if (!anchors.keySet().equals(logicalChunkIds)
                || anchors.values().stream().anyMatch(anchor -> anchor == null
                || !StringUtils.hasText(anchor.sourceDocumentLogicalId())
                || anchor.sourceDocumentSha256() == null || !anchor.sourceDocumentSha256().matches("[0-9a-f]{64}")
                || !StringUtils.hasText(anchor.sourceSectionAnchor()))) {
            throw new IllegalStateException("候选集来源标题锚点必须与逻辑 chunk 一一对应且不能为空");
        }
    }
}
