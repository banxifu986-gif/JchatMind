package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * kb-synthetic-v1 chunk 清单生成器（唯一事实源）：以真实 MarkdownParserServiceImpl 解析
 * datasets/kb-synthetic-v1/working/corpus-draft 全部语料，产出 working/chunk-inventory.jsonl。
 * 契约见 specs/001-kb-synthetic-eval-dataset/contracts/chunk-inventory.schema.md。
 * 解析与 logicalChunkId 方案复制 G2CurrentFixtureRuntimeCorpusLoader（docId#contentPath#chunkIndex）。
 */
@EnabledIfSystemProperty(named = "rag.eval.dataset.inventory.enabled", matches = "true")
class KbSyntheticChunkInventoryGeneratorTest {

    private static final Path CORPUS_DIR = Path.of("..", "datasets", "kb-synthetic-v1", "working", "corpus-draft");
    private static final Path INVENTORY_FILE = Path.of("..", "datasets", "kb-synthetic-v1", "working",
            "chunk-inventory.jsonl");
    /**
     * 边界依据实际产出修正：生产分块器会将 <1000 字符的相邻 section 合并（minPreferredChunkLength=1000），
     * 100 份文档（每份 4~7 section）实际解析出约 617 chunk，原估算 1200~2200 未考虑合并行为（偏差已记录于 spec）。
     */
    private static final int CHUNK_COUNT_MIN = 500;
    private static final int CHUNK_COUNT_MAX = 2500;

    @Test
    void generateInventory() throws IOException {
        MarkdownParserService parser = new MarkdownParserServiceImpl(2000, 0);
        List<Path> corpusFiles;
        try (var files = Files.list(CORPUS_DIR)) {
            corpusFiles = files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
        if (corpusFiles.isEmpty()) {
            throw new IllegalStateException("corpus-draft 为空，先运行 generate_corpus.py: " + CORPUS_DIR.toAbsolutePath());
        }

        ObjectMapperHolder mapper = new ObjectMapperHolder();
        List<String> lines = new ArrayList<>();
        Map<String, Integer> chunkIndexesByPath = new HashMap<>();
        Map<String, String> seenLogicalIds = new LinkedHashMap<>();
        for (Path corpusFile : corpusFiles) {
            String docId = corpusFile.getFileName().toString().replaceFirst("\\.md$", "");
            byte[] bytes = Files.readAllBytes(corpusFile);
            List<MarkdownParserService.MarkdownSection> sections =
                    parser.parseMarkdown(new ByteArrayInputStream(bytes));
            if (sections.isEmpty()) {
                throw new IllegalStateException("文档解析结果为空: " + corpusFile);
            }
            int sectionIndex = 0;
            for (MarkdownParserService.MarkdownSection section : sections) {
                String content = section.getContent() == null ? "" : section.getContent();
                String normalizedPath = section.getContentPath() == null ? ""
                        : section.getContentPath().replaceAll("\\s*>\\s*", ">").trim();
                String pathKey = docId + ">" + normalizedPath;
                int chunkIndex = chunkIndexesByPath.getOrDefault(pathKey, 0);
                chunkIndexesByPath.put(pathKey, chunkIndex + 1);
                String logicalChunkId = docId + "#" + normalizedPath + "#" + chunkIndex;
                if (seenLogicalIds.putIfAbsent(logicalChunkId, docId) != null) {
                    throw new IllegalStateException("logicalChunkId 重复: " + logicalChunkId);
                }
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("logicalChunkId", logicalChunkId);
                record.put("sourceDocumentId", docId);
                record.put("contentPath", normalizedPath);
                record.put("title", section.getTitle());
                record.put("content", content);
                lines.add(mapper.get().writeValueAsString(record));
                sectionIndex++;
            }
        }

        if (lines.size() < CHUNK_COUNT_MIN || lines.size() > CHUNK_COUNT_MAX) {
            throw new IllegalStateException("chunk 总数越界 [" + CHUNK_COUNT_MIN + "," + CHUNK_COUNT_MAX + "]: "
                    + lines.size() + "（调整语料章节结构后重新生成，不得手改清单）");
        }

        Files.createDirectories(INVENTORY_FILE.getParent());
        Files.writeString(INVENTORY_FILE, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        System.out.println("chunk-inventory 生成完成: " + lines.size() + " chunk / " + corpusFiles.size()
                + " 文档 → " + INVENTORY_FILE.toAbsolutePath());
    }

    /** 独立持有 ObjectMapper，避免依赖 Spring 上下文。 */
    private static final class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper value = new com.fasterxml.jackson.databind.ObjectMapper();

        com.fasterxml.jackson.databind.ObjectMapper get() {
            return value;
        }
    }
}
