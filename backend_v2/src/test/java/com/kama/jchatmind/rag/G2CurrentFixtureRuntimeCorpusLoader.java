package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.util.RagChunkSupport;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

record G2CurrentFixtureRuntimeCorpus(
        String datasetId,
        String caseSha256,
        String corpusSha256,
        String fixtureSha256,
        RagEvaluationDatasetManifest manifest,
        List<RagEvaluationCase> cases,
        Map<String, Chunk> chunksByLogicalId
) {
    record Chunk(
            String sourceDocumentId,
            String corpusPath,
            String sourceName,
            String logicalChunkId,
            String contentPath,
            String title,
            String content,
            int chunkIndex,
            int sectionIndex,
            long documentSizeBytes,
            String runtimeDocumentUuid,
            String runtimeChunkUuid,
            String embeddingText,
            String titleSearchText,
            Map<String, Object> metadata
    ) {
    }
}

final class G2CurrentFixtureRuntimeCorpusLoader {

    private static final Map<String, String> CORPUS_PATH_BY_DOCUMENT_ID = Map.of(
            "orders-refund", "../../fixtures/fixture-kb.md",
            "returns-policy", "../../fixtures/fixture-kb-returns.md",
            "logistics", "../../fixtures/fixture-kb-logistics.md",
            "membership", "../../fixtures/fixture-kb-membership.md"
    );

    private G2CurrentFixtureRuntimeCorpusLoader() {
    }

    static G2CurrentFixtureRuntimeCorpus load(String manifestPath) throws IOException {
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(manifestPath);
        if (!"fixture-fast-v1".equals(dataset.manifest().datasetId())) {
            throw new IllegalArgumentException("G2 current fixture runtime 只支持 fixture-fast-v1");
        }
        String fixtureSha256 = G2CurrentFixtureRuntimeImportPlan.fixtureFingerprint(dataset.manifest());

        Set<String> mappedCorpusPaths = new HashSet<>(CORPUS_PATH_BY_DOCUMENT_ID.values());
        if (dataset.manifest().corpusFiles() == null
                || !mappedCorpusPaths.equals(new HashSet<>(dataset.manifest().corpusFiles()))) {
            throw new IllegalStateException("fixture-fast-v1 manifest 的 corpus 文件与 runtime 文档映射不一致");
        }

        Set<String> caseSourceDocumentIds = dataset.cases().stream()
                .filter(item -> item.sourceDocumentIds() != null)
                .flatMap(item -> item.sourceDocumentIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        if (!CORPUS_PATH_BY_DOCUMENT_ID.keySet().containsAll(caseSourceDocumentIds)) {
            throw new IllegalStateException("fixture-fast-v1 case 引用了未映射的来源文档");
        }

        Map<String, G2CurrentFixtureRuntimeCorpus.Chunk> chunksByLogicalId = new LinkedHashMap<>();
        MarkdownParserService parser = new MarkdownParserServiceImpl();
        for (Map.Entry<String, String> document : CORPUS_PATH_BY_DOCUMENT_ID.entrySet()) {
            String corpusResource = resolveSiblingPath(manifestPath, document.getValue());
            String sourceName = fileName(corpusResource);
            String runtimeDocumentUuid = deterministicUuid(
                    dataset.manifest().datasetId() + ":" + fixtureSha256
                            + ":document:" + document.getKey()
            );
            List<MarkdownParserService.MarkdownSection> sections;
            byte[] corpusBytes;
            try (InputStream inputStream = new ClassPathResource(corpusResource).getInputStream()) {
                corpusBytes = inputStream.readAllBytes();
            }
            sections = parser.parseMarkdown(new ByteArrayInputStream(corpusBytes));
            Map<String, Integer> chunkIndexesByPath = new HashMap<>();
            for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
                MarkdownParserService.MarkdownSection section = sections.get(sectionIndex);
                String content = section.getContent() == null ? "" : section.getContent();
                String normalizedPath = normalizeContentPath(section.getContentPath());
                int chunkIndex = chunkIndexesByPath.getOrDefault(normalizedPath, 0);
                chunkIndexesByPath.put(normalizedPath, chunkIndex + 1);
                String logicalChunkId = document.getKey() + "#" + normalizedPath + "#" + chunkIndex;
                Map<String, Object> metadata = RagChunkSupport.buildChunkMetadata(
                        section,
                        "md",
                        sourceName,
                        sectionIndex
                );
                G2CurrentFixtureRuntimeCorpus.Chunk chunk = new G2CurrentFixtureRuntimeCorpus.Chunk(
                        document.getKey(),
                        corpusResource,
                        sourceName,
                        logicalChunkId,
                        section.getContentPath(),
                        section.getTitle(),
                        content,
                        chunkIndex,
                        sectionIndex,
                        corpusBytes.length,
                        runtimeDocumentUuid,
                        deterministicUuid(dataset.manifest().datasetId() + ":"
                                + fixtureSha256 + ":chunk:" + logicalChunkId),
                        RagChunkSupport.buildChunkEmbeddingText(section),
                        RagChunkSupport.buildRetrievableTitleSearchText(section, sourceName),
                        metadata
                );
                if (chunksByLogicalId.putIfAbsent(logicalChunkId, chunk) != null) {
                    throw new IllegalStateException("fixture-fast-v1 runtime logical chunk ID 重复: " + logicalChunkId);
                }
            }
        }

        for (RagEvaluationCase evaluationCase : dataset.cases()) {
            if (evaluationCase.shouldAbstain()) {
                continue;
            }
            for (String logicalChunkId : evaluationCase.goldChunkIds()) {
                G2CurrentFixtureRuntimeCorpus.Chunk chunk = chunksByLogicalId.get(logicalChunkId);
                if (chunk == null) {
                    throw new IllegalStateException("fixture-fast-v1 gold chunk 未映射到 runtime corpus: " + logicalChunkId);
                }
                if (evaluationCase.sourceDocumentIds() == null
                        || !evaluationCase.sourceDocumentIds().contains(chunk.sourceDocumentId())) {
                    throw new IllegalStateException("fixture-fast-v1 gold chunk 映射到错误来源文档: " + logicalChunkId);
                }
            }
        }

        return new G2CurrentFixtureRuntimeCorpus(
                dataset.manifest().datasetId(),
                dataset.manifest().caseSha256(),
                dataset.manifest().corpusSha256(),
                fixtureSha256,
                dataset.manifest(),
                List.copyOf(dataset.cases()),
                Map.copyOf(chunksByLogicalId)
        );
    }

    private static String resolveSiblingPath(String manifestPath, String relativePath) {
        int separator = manifestPath.lastIndexOf('/');
        return manifestPath.substring(0, separator + 1) + relativePath;
    }

    private static String normalizeContentPath(String contentPath) {
        return contentPath == null ? "" : contentPath.replaceAll("\\s*>\\s*", ">").trim();
    }

    private static String fileName(String resourcePath) {
        return resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
    }

    private static String deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
