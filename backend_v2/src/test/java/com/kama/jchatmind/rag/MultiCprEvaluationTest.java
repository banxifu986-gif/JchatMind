package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import com.kama.jchatmind.service.impl.RetrievableTitleLexicalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SpringBootTest(classes = MultiCprEvaluationTest.MultiCprTestConfig.class)
@ActiveProfiles("multi-cpr")
class MultiCprEvaluationTest {

    private static final String KB_NAME_PREFIX = "Multi-CPR";
    private static final int BATCH_LOG_INTERVAL = 500;

    @Autowired
    private RagService ragService;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${multi-cpr.data-dir}")
    private String dataDir;

    @Value("${multi-cpr.domain:ecom}")
    private String domain;

    @Value("${multi-cpr.corpus-sample-size:20000}")
    private int corpusSampleSize;

    @Value("${multi-cpr.eval-limit:10}")
    private int evalLimit;

    @Value("${multi-cpr.rerun-import:false}")
    private boolean rerunImport;

    @Value("${ollama.embedding-model}")
    private String embeddingModel;

    private Path corpusPath;
    private Path queryPath;
    private Path qrelsPath;

    @BeforeEach
    void setUp() {
        String domainDir = dataDir + "/" + domain;
        corpusPath = resolveCorpusPath(domainDir);
        queryPath = Path.of(domainDir, "dev.query.txt");
        qrelsPath = Path.of(domainDir, "qrels.dev.tsv");

        if (!Files.exists(corpusPath)) {
            throw new IllegalStateException("corpus 文件不存在: " + corpusPath);
        }
        if (!Files.exists(queryPath)) {
            throw new IllegalStateException("dev.query.txt 文件不存在: " + queryPath);
        }
        if (!Files.exists(qrelsPath)) {
            throw new IllegalStateException("qrels.dev.tsv 文件不存在: " + qrelsPath);
        }
    }

    private Path resolveCorpusPath(String domainDir) {
        Path single = Path.of(domainDir, "corpus.tsv");
        if (Files.exists(single)) {
            return single;
        }
        Path split1 = Path.of(domainDir, "corpus_split_1.tsv");
        if (Files.exists(split1)) {
            return Path.of(domainDir);
        }
        throw new IllegalStateException("未找到 corpus.tsv 或 corpus_split_1.tsv: " + domainDir);
    }

    @Test
    void evaluateMultiCpr() throws Exception {
        System.out.println("=== Multi-CPR 评测开始 ===");
        System.out.println("领域: " + domain);
        System.out.println("embedding 模型: " + embeddingModel);
        System.out.println("语料采样数: " + corpusSampleSize);

        Map<String, String> queryMap = loadQueries();
        Map<String, List<String>> qrelsMap = loadQrels();
        Set<String> requiredPassageIds = qrelsMap.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        Map<String, String> passageTextMap = loadCorpus(requiredPassageIds);

        String kbName = KB_NAME_PREFIX + " " + domain;
        String kbId = prepareKnowledgeBase(kbName);
        Map<String, String> passageIdToDocDbId = importPassages(kbId, passageTextMap);

        EvaluationResult result = evaluate(kbId, queryMap, qrelsMap, passageIdToDocDbId);

        String report = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Path reportPath = Path.of("target", "multi-cpr-eval", domain + "-report.json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report);
        System.out.println(report);
        System.out.println("评测报告已保存至: " + reportPath.toAbsolutePath());
    }

    private Map<String, String> loadCorpus(Set<String> requiredPassageIds) throws IOException {
        System.out.println("加载 corpus (必需 passage 数: " + requiredPassageIds.size() + ")...");
        Map<String, String> corpus = new LinkedHashMap<>();
        Map<String, String> requiredPassages = new LinkedHashMap<>();
        List<Path> corpusFiles = resolveCorpusFiles();

        int totalLines = 0;
        for (Path file : corpusFiles) {
            totalLines += countLines(file);
        }
        int samplePerFile = corpusFiles.size() == 1
                ? Math.min(corpusSampleSize, totalLines)
                : Math.max(1, corpusSampleSize / corpusFiles.size());

        for (Path file : corpusFiles) {
            List<String> lines = readAllLinesLenient(file);
            for (String line : lines) {
                int tabIdx = line.indexOf('\t');
                if (tabIdx <= 0) {
                    continue;
                }
                String docId = line.substring(0, tabIdx);
                String text = line.substring(tabIdx + 1);
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                if (requiredPassageIds.contains(docId)) {
                    requiredPassages.put(docId, text);
                }
                if (corpus.size() < samplePerFile) {
                    corpus.put(docId, text);
                } else if (requiredPassages.size() >= requiredPassageIds.size()) {
                    break;
                }
            }
            if (corpus.size() >= samplePerFile && requiredPassages.size() >= requiredPassageIds.size()) {
                break;
            }
        }

        corpus.putAll(requiredPassages);

        System.out.println("corpus 加载完成: " + corpus.size() + " 条 (采样 " + (corpus.size() - requiredPassages.size())
                + " + 必需 " + requiredPassages.size() + ")");
        return corpus;
    }

    private List<Path> resolveCorpusFiles() throws IOException {
        if (Files.isDirectory(corpusPath)) {
            return Files.list(corpusPath)
                    .filter(p -> p.getFileName().toString().startsWith("corpus_split_"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        return List.of(corpusPath);
    }

    private List<String> readAllLinesLenient(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        }
    }

    private int countLines(Path file) throws IOException {
        return readAllLinesLenient(file).size();
    }

    private Map<String, String> loadQueries() throws IOException {
        Map<String, String> queries = new LinkedHashMap<>();
        List<String> lines = readAllLinesLenient(queryPath);
        for (String line : lines) {
            int tabIdx = line.indexOf('\t');
            if (tabIdx <= 0) {
                continue;
            }
            String queryId = line.substring(0, tabIdx).trim();
            String queryText = line.substring(tabIdx + 1).trim();
            if (StringUtils.hasText(queryText)) {
                queries.put(queryId, queryText);
            }
        }
        System.out.println("query 加载完成: " + queries.size() + " 条");
        return queries;
    }

    private Map<String, List<String>> loadQrels() throws IOException {
        Map<String, List<String>> qrels = new LinkedHashMap<>();
        List<String> lines = readAllLinesLenient(qrelsPath);
        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length < 4) {
                continue;
            }
            String queryId = parts[0].trim();
            String docId = parts[2].trim();
            qrels.computeIfAbsent(queryId, k -> new ArrayList<>()).add(docId);
        }
        System.out.println("qrels 加载完成: " + qrels.size() + " 条 query 有标注");
        return qrels;
    }

    private String prepareKnowledgeBase(String kbName) {
        for (KnowledgeBase kb : knowledgeBaseMapper.selectAll()) {
            if (kbName.equals(kb.getName())) {
                if (rerunImport) {
                    cleanupExistingData(kb.getId());
                } else {
                    System.out.println("知识库已存在，跳过导入: " + kbName);
                    return kb.getId();
                }
            }
        }

        KnowledgeBase kb = KnowledgeBase.builder()
                .name(kbName)
                .description("Multi-CPR " + domain + " 评测知识库")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        knowledgeBaseMapper.insert(kb);
        System.out.println("知识库已创建: " + kb.getId());
        return kb.getId();
    }

    private void cleanupExistingData(String kbId) {
        System.out.println("清理已有数据 (kbId=" + kbId + ")...");
        for (Document doc : documentMapper.selectByKbId(kbId)) {
            chunkBgeM3Mapper.selectByDocId(doc.getId()).forEach(
                    chunk -> chunkBgeM3Mapper.deleteById(chunk.getId())
            );
            documentMapper.deleteById(doc.getId());
        }
    }

    private Map<String, String> importPassages(
            String kbId,
            Map<String, String> passageTextMap
    ) {
        Map<String, String> passageIdToDocDbId = new LinkedHashMap<>();
        int total = passageTextMap.size();
        int imported = 0;
        LocalDateTime now = LocalDateTime.now();

        System.out.println("开始导入 " + total + " 条 passage (embedding 模型: " + embeddingModel + ")...");
        long startTime = System.currentTimeMillis();

        for (Map.Entry<String, String> entry : passageTextMap.entrySet()) {
            String passageId = entry.getKey();
            String text = entry.getValue();

            Document doc = Document.builder()
                    .kbId(kbId)
                    .filename("multi-cpr-" + passageId)
                    .filetype("tsv")
                    .size((long) text.length())
                    .metadata(buildDocumentMetadata(domain, passageId))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            documentMapper.insert(doc);

            float[] embedding = ragService.embed(text);

            String chunkMetadata = buildChunkMetadata(domain, text);
            ChunkBgeM3 chunk = ChunkBgeM3.builder()
                    .kbId(kbId)
                    .docId(doc.getId())
                    .content(text)
                    .metadata(chunkMetadata)
                    .embedding(embedding)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            chunkBgeM3Mapper.insert(chunk);

            passageIdToDocDbId.put(passageId, doc.getId());
            imported++;

            if (imported % BATCH_LOG_INTERVAL == 0) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                double rate = imported / Math.max(elapsed, 1.0);
                System.out.printf("  进度: %d/%d (%.1f%%), 耗时: %ds, 速率: %.1f 条/s%n",
                        imported, total, imported * 100.0 / total, elapsed, rate);
            }
        }

        long totalSec = Math.max((System.currentTimeMillis() - startTime) / 1000, 1);
        System.out.printf("导入完成: %d 条, 总耗时: %ds, 平均速率: %.1f 条/s%n",
                imported, totalSec, (double) imported / totalSec);
        return passageIdToDocDbId;
    }

    private String buildDocumentMetadata(String domain, String passageId) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", "multi-cpr");
            meta.put("domain", domain);
            meta.put("passageId", passageId);
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 Document metadata 失败", e);
        }
    }

    private String buildChunkMetadata(String domain, String text) {
        String normalizedTitle = RetrievableTitleLexicalizer.normalize(
                text.length() > 80 ? text.substring(0, 80) : text
        );
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("title", normalizedTitle);
            metadata.put("retrievableTitle", normalizedTitle);
            metadata.put("retrievableTitleSearchText",
                    RetrievableTitleLexicalizer.buildSearchText(normalizedTitle, text));
            metadata.put("contentPath", "multi-cpr");
            metadata.put("sourceType", domain);
            metadata.put("sourceName", "multi-cpr");
            metadata.put("sectionIndex", 0);
            metadata.put("headingLevel", 1);
            metadata.put("hasChildren", false);
            metadata.put("sectionType", "LEAF_CONTENT");
            metadata.put("pathDepth", 1);
            metadata.put("localContentLength", text.length());
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 Chunk metadata 失败", e);
        }
    }

    private EvaluationResult evaluate(
            String kbId,
            Map<String, String> queryMap,
            Map<String, List<String>> qrelsMap,
            Map<String, String> passageIdToDocDbId
    ) {
        List<String> kbIds = List.of(kbId);
        int total = qrelsMap.size();
        int evaluable = 0;
        int skipped = 0;
        int hitAt1 = 0;
        int hitAt3 = 0;
        int hitAt5 = 0;
        int hitAt10 = 0;
        double mrrSum = 0D;
        List<String> missCases = new ArrayList<>();

        System.out.println("开始评测 " + total + " 条 query...");
        int processed = 0;
        long startTime = System.currentTimeMillis();

        for (Map.Entry<String, List<String>> entry : qrelsMap.entrySet()) {
            String queryId = entry.getKey();
            List<String> goldPassageIds = entry.getValue();

            String queryText = queryMap.get(queryId);
            if (!StringUtils.hasText(queryText)) {
                skipped++;
                continue;
            }

            List<String> goldDocDbIds = goldPassageIds.stream()
                    .map(passageIdToDocDbId::get)
                    .filter(id -> id != null)
                    .toList();
            if (goldDocDbIds.isEmpty()) {
                skipped++;
                continue;
            }

            List<RagRetrievalResult> results = ragService.retrieve(kbIds, queryText, evalLimit);
            List<String> retrievedDocIds = results.stream()
                    .map(RagRetrievalResult::getDocId)
                    .toList();

            boolean hit1 = false;
            boolean hit3 = false;
            boolean hit5 = false;
            boolean hit10 = false;
            double reciprocalRank = 0D;

            for (int i = 0; i < results.size(); i++) {
                if (goldDocDbIds.contains(retrievedDocIds.get(i))) {
                    reciprocalRank = 1.0 / (i + 1);
                    if (i < 1) hit1 = true;
                    if (i < 3) hit3 = true;
                    if (i < 5) hit5 = true;
                    if (i < 10) hit10 = true;
                    break;
                }
            }

            if (hit1) hitAt1++;
            if (hit3) hitAt3++;
            if (hit5) hitAt5++;
            if (hit10) hitAt10++;
            mrrSum += reciprocalRank;
            evaluable++;

            if (!hit5) {
                missCases.add(queryId + " | " + queryText + " | gold=" + goldPassageIds
                        + " | top=" + retrievedDocIds.subList(0, Math.min(5, retrievedDocIds.size())));
            }

            processed++;
            if (processed % 100 == 0) {
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                System.out.printf("  评测进度: %d/%d, 耗时: %.1fs, 当前Recall@10: %.3f%n",
                        processed, total, elapsed, (double) hitAt10 / evaluable);
            }
        }

        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
        double recallAt1 = ratio(hitAt1, evaluable);
        double recallAt3 = ratio(hitAt3, evaluable);
        double recallAt5 = ratio(hitAt5, evaluable);
        double recallAt10 = ratio(hitAt10, evaluable);
        double mrr = evaluable > 0 ? mrrSum / evaluable : 0D;

        System.out.printf("评测完成: %d 条, 耗时: %.1fs%n", evaluable, elapsed);

        return new EvaluationResult(
                domain,
                embeddingModel,
                corpusSampleSize,
                total,
                evaluable,
                skipped,
                recallAt1,
                recallAt3,
                recallAt5,
                recallAt10,
                mrr,
                hitAt1,
                hitAt3,
                hitAt5,
                hitAt10,
                missCases.size(),
                missCases.subList(0, Math.min(20, missCases.size()))
        );
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }

    @Configuration
    @EnableAutoConfiguration
    @ImportAutoConfiguration({
            JacksonAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            MybatisAutoConfiguration.class
    })
    @MapperScan("com.kama.jchatmind.mapper")
    @Import({
            QueryRewriteServiceImpl.class,
            RagServiceImpl.class
    })
    static class MultiCprTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private record EvaluationResult(
            String domain,
            String embeddingModel,
            int corpusSize,
            int totalQueries,
            int evaluable,
            int skipped,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double recallAt10,
            double mrr,
            int hitAt1,
            int hitAt3,
            int hitAt5,
            int hitAt10,
            int missCount,
            List<String> missCases
    ) {
    }
}
