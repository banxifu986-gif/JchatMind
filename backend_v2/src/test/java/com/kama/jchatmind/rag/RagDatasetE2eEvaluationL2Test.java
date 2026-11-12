package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import com.kama.jchatmind.service.impl.VchordBm25QueryService;
import com.kama.jchatmind.util.RagChunkSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * kb-synthetic-v1 统一端到端评测入口：冻结数据集加载 → 隔离库导入（批量 embed）→ 逐条检索 replay（断点续跑）
 * → L1 指标 → 答案生成 + judge 最小接入。规格见 specs/001-kb-synthetic-eval-dataset。
 */
@SpringBootTest(
        classes = RagDatasetE2eEvaluationL2Test.Config.class,
        properties = {
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval",
                "spring.datasource.username=rag_eval",
                "spring.datasource.password=",
                "document.storage.base-path=target/rag-eval/dataset-e2e/uploads"
        }
)
@ActiveProfiles("multi-cpr")
@EnabledIfSystemProperty(named = "rag.eval.dataset.e2e.enabled", matches = "true")
class RagDatasetE2eEvaluationL2Test {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/kb-synthetic-v1.json";
    private static final String ANSWER_GOLD_PATH = "rag-eval/datasets/answer-gold/kb-synthetic-v1-answer-gold.jsonl";
    private static final String REGISTRY_FILE = "../rag-eval/external-benchmark-registry.json";
    private static final String REPLAY_RESOURCE = "rag-eval/replays/kb-synthetic-v1-replay.jsonl";
    private static final Path REPLAY_FS_PATH = Path.of("target", "test-classes", "rag-eval", "replays",
            "kb-synthetic-v1-replay.jsonl");
    private static final Path REPORT_DIR = Path.of("target", "rag-dataset-e2e");
    private static final String KB_NAME = "kb-synthetic-v1 e2e";
    private static final String SOURCE_TYPE = "md";
    private static final int CONTEXT_CHUNK_COUNT = 3;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

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

    @Autowired
    private MarkdownParserService markdownParserService;

    @Autowired
    private ChatClient judgeClient;

    @Value("${rag.eval.dataset.e2e.limit:0}")
    private int limit;

    @Value("${rag.eval.dataset.e2e.rerun-import:false}")
    private boolean rerunImport;

    @Value("${rag.eval.dataset.e2e.embed-batch-size:32}")
    private int embedBatchSize;

    @Value("${rag.eval.dataset.e2e.judge.enabled:false}")
    private boolean judgeEnabled;

    @Value("${rag.eval.dataset.e2e.judge.limit:20}")
    private int judgeLimit;

    @Value("${rag.eval.dataset.e2e.judge.model:deepseek-chat}")
    private String judgeModel;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.embedding-model}")
    private String embeddingModel;

    @Test
    void evaluateDatasetE2e() throws Exception {
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(MANIFEST_PATH);
        RagEvaluationDatasetManifest manifest = dataset.manifest();
        System.out.println("=== kb-synthetic-v1 端到端评测开始 ===");
        System.out.println("datasetId: " + manifest.datasetId() + ", case 数: " + dataset.cases().size()
                + ", limit: " + limit + ", rerun-import: " + rerunImport);

        verifyAnswerGoldChecksum();

        List<RuntimeChunk> chunks = buildRuntimeChunks(manifest);
        ImportOutcome outcome = prepareKnowledgeBaseAndImport(manifest, chunks);
        if (outcome.chunkIdToLogical().isEmpty()) {
            throw new IllegalStateException("chunk 映射为空，无法进行检索 replay");
        }

        Map<String, String> contextsByCaseId = replayRetrieval(dataset, outcome.kbId(), outcome.chunkIdToLogical());
        List<String> retrievedOrder = lastRetrievedCaseIds;

        runMetricsIfComplete();
        if (judgeEnabled) {
            runJudgeSubset(dataset, contextsByCaseId, retrievedOrder);
        }
    }

    private List<RuntimeChunk> buildRuntimeChunks(RagEvaluationDatasetManifest manifest) throws IOException {
        List<RuntimeChunk> chunks = new ArrayList<>();
        Map<String, Integer> chunkIndexesByPath = new HashMap<>();
        int documentCount = 0;
        for (String corpusFile : manifest.corpusFiles()) {
            String resource = resolveSiblingPath(MANIFEST_PATH, corpusFile);
            String docId = fileName(corpusFile).replaceFirst("\\.md$", "");
            String sourceName = fileName(corpusFile);
            byte[] corpusBytes;
            try (InputStream inputStream = new ClassPathResource(resource).getInputStream()) {
                corpusBytes = inputStream.readAllBytes();
            }
            List<MarkdownParserService.MarkdownSection> sections =
                    markdownParserService.parseMarkdown(new ByteArrayInputStream(corpusBytes));
            int sectionIndex = 0;
            for (MarkdownParserService.MarkdownSection section : sections) {
                String content = section.getContent() == null ? "" : section.getContent();
                String normalizedPath = normalizeContentPath(section.getContentPath());
                String pathKey = docId + ">" + normalizedPath;
                int chunkIndex = chunkIndexesByPath.getOrDefault(pathKey, 0);
                chunkIndexesByPath.put(pathKey, chunkIndex + 1);
                String logicalChunkId = docId + "#" + normalizedPath + "#" + chunkIndex;
                Map<String, Object> metadata = RagChunkSupport.buildChunkMetadata(
                        section, SOURCE_TYPE, sourceName, sectionIndex
                );
                metadata.put("sourceDocumentId", docId);
                metadata.put("logicalChunkId", logicalChunkId);
                chunks.add(new RuntimeChunk(
                        docId,
                        logicalChunkId,
                        objectMapper.writeValueAsString(metadata),
                        RagChunkSupport.buildChunkEmbeddingText(section),
                        content
                ));
                sectionIndex++;
            }
            documentCount++;
        }
        System.out.println("chunk 清单构建完成: " + chunks.size() + " chunk / " + documentCount + " 文档");
        return chunks;
    }

    /**
     * KB 不存在或 rerun-import 时全量导入；KB 已存在且不 rerun 时从 chunk metadata 重建映射（跳过导入）。
     */
    private record ImportOutcome(String kbId, Map<String, String> chunkIdToLogical) {
    }

    private ImportOutcome prepareKnowledgeBaseAndImport(
            RagEvaluationDatasetManifest manifest,
            List<RuntimeChunk> chunks
    ) throws IOException {
        Map<String, String> chunkIdToLogical = new LinkedHashMap<>();
        KnowledgeBase existing = null;
        for (KnowledgeBase kb : knowledgeBaseMapper.selectAll()) {
            if (KB_NAME.equals(kb.getName())) {
                existing = kb;
                break;
            }
        }

        if (existing != null && !rerunImport) {
            rebuildMappingFromDb(existing.getId(), chunkIdToLogical);
            System.out.println("知识库已存在，复用并重建映射: " + chunkIdToLogical.size() + " chunk");
            return new ImportOutcome(existing.getId(), chunkIdToLogical);
        }

        String kbId;
        if (existing != null) {
            cleanupExistingData(existing.getId());
            kbId = existing.getId();
        } else {
            KnowledgeBase kb = KnowledgeBase.builder()
                    .name(KB_NAME)
                    .description("kb-synthetic-v1 端到端评测知识库")
                    .metadata("{}")
                    .ownerId("900000000001")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            knowledgeBaseMapper.insert(kb);
            kbId = kb.getId();
            System.out.println("知识库已创建: " + kbId);
        }

        Map<String, String> documentDbIdByDocId = new LinkedHashMap<>();
        for (String docId : chunks.stream().map(RuntimeChunk::documentId).distinct().toList()) {
            Document doc = Document.builder()
                    .kbId(kbId)
                    .filename(docId + ".md")
                    .filetype(SOURCE_TYPE)
                    .size(0L)
                    .metadata(documentMetadata(manifest, docId))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            documentMapper.insert(doc);
            documentDbIdByDocId.put(docId, doc.getId());
        }

        MmarcoZhSampledOllamaBatchEmbedder batchEmbedder = new MmarcoZhSampledOllamaBatchEmbedder(
                WebClient.builder(), ollamaBaseUrl, embeddingModel, embedBatchSize
        );
        long startTime = System.currentTimeMillis();
        List<float[]> embeddings = batchEmbedder.embedAll(
                chunks.stream().map(RuntimeChunk::embeddingText).toList()
        );
        long elapsedMs = System.currentTimeMillis() - startTime;
        System.out.printf("批量 embedding 完成: %d 条, 耗时 %dms, 速率 %.1f chunk/s%n",
                embeddings.size(), elapsedMs, embeddings.size() * 1000.0 / Math.max(elapsedMs, 1));

        for (int i = 0; i < chunks.size(); i++) {
            RuntimeChunk item = chunks.get(i);
            ChunkBgeM3 chunk = ChunkBgeM3.builder()
                    .kbId(kbId)
                    .docId(documentDbIdByDocId.get(item.documentId()))
                    .content(item.content())
                    .metadata(item.metadataJson())
                    .embedding(embeddings.get(i))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            chunkBgeM3Mapper.insert(chunk);
            if (chunk.getId() == null) {
                throw new IllegalStateException("chunk 主键未回填: " + item.logicalChunkId());
            }
            chunkIdToLogical.put(chunk.getId(), item.logicalChunkId());
        }
        System.out.println("导入完成: " + chunks.size() + " chunk");
        return new ImportOutcome(kbId, chunkIdToLogical);
    }

    private void rebuildMappingFromDb(String kbId, Map<String, String> chunkIdToLogical) {
        for (Document doc : documentMapper.selectByKbId(kbId)) {
            for (ChunkBgeM3 chunk : chunkBgeM3Mapper.selectByDocId(doc.getId())) {
                String logicalChunkId = readMetadataField(chunk.getMetadata(), "logicalChunkId");
                if (logicalChunkId != null) {
                    chunkIdToLogical.put(chunk.getId(), logicalChunkId);
                }
            }
        }
    }

    private String readMetadataField(String metadataJson, String field) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson).get(field);
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 逐条检索 replay，replay 文件断点续跑（已完成 case 跳过）。返回本运行内检索 case 的 context（供 judge 使用）。
     */
    private List<String> lastRetrievedCaseIds = new ArrayList<>();

    private Map<String, String> replayRetrieval(
            RagEvaluationDataset dataset,
            String kbId,
            Map<String, String> chunkIdToLogical
    ) throws IOException {
        Map<String, RagFastRegressionReplay> existingReplays = loadExistingReplays();
        List<String> linesToWrite = new ArrayList<>();
        Map<String, String> contextsByCaseId = new LinkedHashMap<>();
        List<String> retrievedThisRun = new ArrayList<>();
        RagRouter router = new RagRouter();
        int executed = 0;

        for (RagEvaluationCase item : dataset.cases()) {
            if (limit > 0 && executed >= limit) {
                break;
            }
            RagFastRegressionReplay existing = existingReplays.get(item.caseId());
            if (existing != null) {
                linesToWrite.add(objectMapper.writeValueAsString(existing));
                continue;
            }
            long startedAt = System.nanoTime();
            List<RagRetrievalResult> results = ragService.retrieve(List.of(kbId), item.query(), 10);
            long latencyMs = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
            List<String> topChunkIds = results.stream()
                    .map(RagRetrievalResult::getChunkId)
                    .map(id -> chunkIdToLogical.getOrDefault(id, id))
                    .toList();
            boolean abstained = router.decide(item.query(), item.kbScope(), true, false, true)
                    .route() == RagRouteDecision.Route.ABSTAIN;
            RagFastRegressionReplay replay = new RagFastRegressionReplay(item.caseId(), topChunkIds, abstained, latencyMs);
            linesToWrite.add(objectMapper.writeValueAsString(replay));
            if (!item.shouldAbstain()) {
                contextsByCaseId.put(item.caseId(), joinContext(results));
            }
            retrievedThisRun.add(item.caseId());
            executed++;
            if (executed % 50 == 0) {
                System.out.println("replay 进度: " + executed + " 条");
            }
        }

        Files.createDirectories(REPLAY_FS_PATH.getParent());
        Files.writeString(REPLAY_FS_PATH, String.join("\n", linesToWrite) + "\n", StandardCharsets.UTF_8);
        System.out.println("replay 写入完成: 本次执行 " + executed + " 条, 文件共 " + linesToWrite.size() + " 条 → "
                + REPLAY_FS_PATH.toAbsolutePath());
        lastRetrievedCaseIds = retrievedThisRun;
        return contextsByCaseId;
    }

    private Map<String, RagFastRegressionReplay> loadExistingReplays() throws IOException {
        Map<String, RagFastRegressionReplay> replays = new LinkedHashMap<>();
        if (!Files.exists(REPLAY_FS_PATH)) {
            return replays;
        }
        for (String line : Files.readString(REPLAY_FS_PATH, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            RagFastRegressionReplay replay = objectMapper.readValue(line, RagFastRegressionReplay.class);
            replays.put(replay.caseId(), replay);
        }
        System.out.println("断点续跑: 已有 replay " + replays.size() + " 条, 跳过已完成 case");
        return replays;
    }

    private void runMetricsIfComplete() throws IOException {
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(MANIFEST_PATH);
        Map<String, RagFastRegressionReplay> replays = loadExistingReplays();
        if (replays.size() != dataset.cases().size()) {
            System.out.println("replay 未覆盖全部 case (" + replays.size() + "/" + dataset.cases().size()
                    + ")，跳过 L1 指标，继续断点续跑");
            return;
        }
        Path reportPath = REPORT_DIR.resolve("kb-synthetic-v1-fast-report-" + LocalDateTime.now().format(TIMESTAMP) + ".json");
        RagFastRegressionReport report = RagFastRegressionEvaluator.evaluateAndWrite(
                MANIFEST_PATH, REPLAY_RESOURCE, reportPath
        );
        System.out.println("L1 指标报告: " + reportPath.toAbsolutePath());
        System.out.println("Recall@5=" + report.recallAt5() + " MRR@3=" + report.mrrAt3()
                + " abstentionAccuracy=" + report.abstentionAccuracy());
    }

    private void runJudgeSubset(RagEvaluationDataset dataset, Map<String, String> contextsByCaseId,
                                List<String> retrievedOrder) throws IOException {
        if (retrievedOrder.isEmpty()) {
            System.out.println("judge 跳过: 本次运行没有新检索的 case（全部命中断点续跑缓存）");
            return;
        }
        int from = Math.max(0, retrievedOrder.size() - judgeLimit);
        List<String> subsetIds = retrievedOrder.subList(from, retrievedOrder.size());
        List<RagEvaluationCase> subset = dataset.cases().stream()
                .filter(item -> subsetIds.contains(item.caseId()))
                .toList();
        for (RagEvaluationCase item : subset) {
            if (!item.shouldAbstain() && !contextsByCaseId.containsKey(item.caseId())) {
                System.out.println("judge 跳过: case " + item.caseId() + " 本次运行未检索");
                return;
            }
        }
        System.out.println("judge 子集: 本次检索的尾部 " + subset.size() + " 条");

        RagRouter router = new RagRouter();
        int answerable = (int) subset.stream().filter(item -> !item.shouldAbstain()).count();
        Map<String, Integer> routeCounts = new LinkedHashMap<>();
        List<G2CurrentFixtureCase> profileCases = subset.stream()
                .map(item -> new G2CurrentFixtureCase(
                        item.caseId(),
                        item.query(),
                        item.queryType(),
                        List.copyOf(item.goldChunkIds()),
                        List.copyOf(item.sourceDocumentIds()),
                        item.shouldAbstain(),
                        !item.shouldAbstain(),
                        router.decide(item.query(), item.kbScope(), true, false, true).route().name()
                ))
                .toList();
        profileCases.forEach(item -> routeCounts.merge(item.expectedRoute(), 1, Integer::sum));
        Map<String, String> subsetContexts = new LinkedHashMap<>();
        for (RagEvaluationCase item : subset) {
            subsetContexts.put(item.caseId(), item.shouldAbstain() ? "" : contextsByCaseId.get(item.caseId()));
        }
        G2CurrentFixtureProfile profile = new G2CurrentFixtureProfile(
                dataset.manifest().datasetId(),
                subset.size(),
                answerable,
                subset.size() - answerable,
                (int) subset.stream().filter(item -> "follow_up_contextual_rewrite".equals(item.queryType())).count(),
                (int) subset.stream().filter(item -> "cross_document".equals(item.queryType())).count(),
                profileCases,
                routeCounts
        );

        G2CurrentFixtureAnswerGenerationEvaluator generationEvaluator =
                new G2CurrentFixtureAnswerGenerationEvaluator(judgeClient, judgeModel);
        G2CurrentFixtureAnswerGenerationEvaluator.Report generation =
                generationEvaluator.generate(profile, subsetContexts);
        Map<String, String> answersByCaseId = new LinkedHashMap<>(generation.finalAnswersByCaseId());
        for (RagEvaluationCase item : subset) {
            answersByCaseId.putIfAbsent(item.caseId(), "");
        }

        G2CurrentFixtureAnswerQualityEvaluator qualityEvaluator =
                new G2CurrentFixtureAnswerQualityEvaluator(judgeClient, judgeModel);
        G2CurrentFixtureAnswerQualityReport quality = qualityEvaluator.evaluate(profile, subsetContexts, answersByCaseId);

        Map<String, Object> judgeReport = new LinkedHashMap<>();
        judgeReport.put("datasetId", dataset.manifest().datasetId());
        judgeReport.put("judgeModel", judgeModel);
        judgeReport.put("promptVersion", G2CurrentFixtureAnswerGenerationEvaluator.SYSTEM_PROMPT_VERSION);
        judgeReport.put("temperature", "model-default");
        judgeReport.put("generationStatus", generation.status());
        judgeReport.put("quality", quality);
        Files.createDirectories(REPORT_DIR);
        Path reportPath = REPORT_DIR.resolve(
                "kb-synthetic-v1-judge-report-" + LocalDateTime.now().format(TIMESTAMP) + ".json");
        Files.writeString(reportPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(judgeReport),
                StandardCharsets.UTF_8);
        System.out.println("judge 报告: " + reportPath.toAbsolutePath());
        System.out.println("faithfulness=" + quality.meanFaithfulness() + " answerRelevancy=" + quality.meanAnswerRelevancy()
                + " status=" + quality.status());
    }

    private void verifyAnswerGoldChecksum() throws IOException {
        ClassPathResource resource = new ClassPathResource(ANSWER_GOLD_PATH);
        if (!resource.exists()) {
            System.out.println("answer-gold 尚未冻结，跳过校验和自检");
            return;
        }
        JsonNode registry;
        try (InputStream inputStream = Files.newInputStream(Path.of(REGISTRY_FILE))) {
            registry = objectMapper.readTree(inputStream.readAllBytes());
        }
        JsonNode artifacts = registry.path("kbSyntheticV1").path("artifacts");
        String expected = artifacts.path("answerGoldSha256").asText(null);
        if (expected == null) {
            throw new IllegalStateException("registry 缺少 kbSyntheticV1.answerGoldSha256: " + REGISTRY_FILE);
        }
        String actual = sha256(resource.getInputStream().readAllBytes());
        if (!expected.equals(actual)) {
            throw new IllegalStateException("answer-gold 校验和不匹配: expected=" + expected + " actual=" + actual);
        }
        System.out.println("answer-gold 校验和自检通过");
    }

    private void cleanupExistingData(String kbId) {
        System.out.println("清理已有数据 (kbId=" + kbId + ")...");
        for (Document doc : documentMapper.selectByKbId(kbId)) {
            chunkBgeM3Mapper.selectByDocId(doc.getId()).forEach(chunk -> chunkBgeM3Mapper.deleteById(chunk.getId()));
            documentMapper.deleteById(doc.getId());
        }
    }

    private String documentMetadata(RagEvaluationDatasetManifest manifest, String docId) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("datasetId", manifest.datasetId());
            meta.put("sourceDocumentId", docId);
            meta.put("sourceType", SOURCE_TYPE);
            return objectMapper.writeValueAsString(meta);
        } catch (IOException e) {
            throw new IllegalStateException("序列化 document metadata 失败", e);
        }
    }

    private String joinContext(List<RagRetrievalResult> results) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(CONTEXT_CHUNK_COUNT, results.size()); i++) {
            if (i > 0) {
                builder.append("\n\n");
            }
            builder.append(results.get(i).getContent() == null ? "" : results.get(i).getContent());
        }
        return builder.toString();
    }

    private static String resolveSiblingPath(String manifestPath, String relativePath) {
        int separator = manifestPath.lastIndexOf('/');
        return manifestPath.substring(0, separator + 1) + relativePath;
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String normalizeContentPath(String contentPath) {
        return contentPath == null ? "" : contentPath.replaceAll("\\s*>\\s*", ">").trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static final class RuntimeChunk {
        private final String documentId;
        private final String logicalChunkId;
        private final String metadataJson;
        private final String embeddingText;
        private final String content;

        private RuntimeChunk(String documentId, String logicalChunkId, String metadataJson,
                             String embeddingText, String content) {
            this.documentId = documentId;
            this.logicalChunkId = logicalChunkId;
            this.metadataJson = metadataJson;
            this.embeddingText = embeddingText;
            this.content = content;
        }

        String documentId() {
            return documentId;
        }

        String logicalChunkId() {
            return logicalChunkId;
        }

        String metadataJson() {
            return metadataJson;
        }

        String embeddingText() {
            return embeddingText;
        }

        String content() {
            return content;
        }
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
            RagServiceImpl.class,
            ChatClientRegistry.class
    })
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MarkdownParserService markdownParserService() {
            return new MarkdownParserServiceImpl(2000, 0);
        }

        @Bean
        VchordBm25QueryService vchordBm25QueryService(
                com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper tokenDictionaryMapper,
                ChunkBgeM3Mapper chunkBgeM3Mapper,
                org.springframework.jdbc.core.JdbcTemplate jdbcTemplate
        ) {
            return new VchordBm25QueryService(tokenDictionaryMapper, chunkBgeM3Mapper, jdbcTemplate);
        }

        @Bean
        com.kama.jchatmind.service.impl.BgeRerankerService bgeRerankerService(
                WebClient.Builder webClientBuilder,
                @Value("${rag.rerank.enabled:false}") boolean enabled,
                @Value("${rag.rerank.base-url:http://127.0.0.1:8081}") String baseUrl,
                @Value("${rag.rerank.timeout-ms:3000}") int timeoutMs
        ) {
            return new com.kama.jchatmind.service.impl.BgeRerankerService(webClientBuilder, enabled, baseUrl, timeoutMs);
        }

        @Bean("deepseek-chat")
        ChatClient deepseekChatClient(DeepSeekChatModel deepSeekChatModel) {
            return ChatClient.create(deepSeekChatModel);
        }
    }
}
