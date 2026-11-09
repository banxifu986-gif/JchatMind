package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.ingestion.DeepSeekVisionClient;
import com.kama.jchatmind.ingestion.DocumentImageExtractionWorker;
import com.kama.jchatmind.ingestion.PdfImageExtractor;
import com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentAssetExtractionMapper;
import com.kama.jchatmind.mapper.DocumentAssetMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dmi-image-rag-v1 真实模型评测 L2：
 * 隔离库导入 → 真实 DeepSeek 视觉抽取 worker → 图片资产检索 → 读图引用增强 → 报告。
 * 显式 opt-in：-Ddmi.image-rag.eval.enabled=true，DeepSeek key 经环境变量 SPRING_AI_DEEPSEEK_API_KEY 提供。
 */
@EnabledIfSystemProperty(named = "dmi.image-rag.eval.enabled", matches = "true")
class DmiImageRagEvalL2Test {

    private static final String OWNER_ID = "900000000005";
    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/dmi-image-rag-v1.json";
    private static final Path EVAL_UPLOADS = Path.of("rag-eval", "uploads").toAbsolutePath();
    private static final Path REPORT_PATH =
            Path.of("target", "rag-eval", "dmi", "dmi-image-rag-v1-runtime.json");

    @Test
    void runImageEvidenceEvaluation() throws Exception {
        String ollamaBaseUrl = System.getProperty("dmi.eval.ollama.base-url", "http://127.0.0.1:11434");
        String visionModel = System.getProperty("dmi.eval.ollama.vision-model", "qwen2.5vl:7b");
        String embeddingModel = System.getProperty("dmi.eval.ollama.embedding-model", "bge-m3:latest");
        MmarcoZhSampledOllamaBatchEmbedder embedder =
                new MmarcoZhSampledOllamaBatchEmbedder(WebClient.builder(), ollamaBaseUrl, embeddingModel, 8);

        DmiImageRagCandidateFixtureLoader.DmiCandidateProfile profile =
                DmiImageRagCandidateFixtureLoader.load(MANIFEST_PATH);
        String manifestSha256 = profile.manifestSha256();
        String knowledgeBaseId = DmiImageRagEvalRuntimeBuilder.knowledgeBaseId(manifestSha256);

        MarkdownParserService parser = new MarkdownParserServiceImpl();
        PdfImageExtractor imageExtractor = new PdfImageExtractor();
        List<DmiImageRagEvalRuntimeBuilder.DmiDocumentRow> documents = new ArrayList<>();
        List<DmiImageRagEvalRuntimeBuilder.DmiChunkRow> chunks = new ArrayList<>();
        List<DmiImageRagEvalRuntimeBuilder.DmiAssetRow> assets = new ArrayList<>();
        List<DmiImageRagEvalRuntimeBuilder.DmiRelationRow> relations = new ArrayList<>();
        List<String> chunkEmbeddingTexts = new ArrayList<>();

        for (DmiImageRagCandidateFixtureLoader.DmiCandidateCase candidateCase : profile.cases()) {
            if (!StringUtilsHasText(candidateCase.sourceGenerator())) {
                continue;
            }
            byte[] pdfBytes = DmiImageRagCandidateFixtureLoader.generatePdf(
                    candidateCase.pageText(), candidateCase.sourceText(), candidateCase.imageKind());
            String documentId = DmiImageRagEvalRuntimeBuilder.documentId(
                    manifestSha256, candidateCase.sourceDocumentId());
            documents.add(new DmiImageRagEvalRuntimeBuilder.DmiDocumentRow(
                    documentId,
                    knowledgeBaseId,
                    "generated-" + candidateCase.sourceGenerator() + ".pdf",
                    "pdf",
                    pdfBytes.length,
                    evalMetadata(manifestSha256, candidateCase.sourceDocumentId())
            ));

            List<MarkdownParserService.MarkdownSection> pages =
                    parser.parsePdf(new ByteArrayInputStream(pdfBytes));
            List<PdfImageExtractor.PdfEmbeddedImage> images =
                    imageExtractor.extract(new ByteArrayInputStream(pdfBytes));
            assertThat(pages).hasSize(1);
            assertThat(images).hasSize(1);
            MarkdownParserService.MarkdownSection page = pages.get(0);
            PdfImageExtractor.PdfEmbeddedImage image = images.get(0);

            String logicalChunkId = candidateCase.sourceDocumentId() + "#"
                    + (page.getContentPath() == null ? "" : page.getContentPath().trim()) + "#0";
            String pageChunkId = DmiImageRagEvalRuntimeBuilder.chunkId(manifestSha256, logicalChunkId);
            Map<String, Object> chunkMetadata = evalMetadata(manifestSha256, candidateCase.sourceDocumentId());
            chunkMetadata.put("logicalChunkId", logicalChunkId);
            chunkMetadata.put("title", page.getTitle());
            chunkMetadata.put("contentPath", page.getContentPath());
            chunkMetadata.put("sourceType", "pdf");
            chunkMetadata.put("sourceName", "generated-" + candidateCase.sourceGenerator() + ".pdf");
            chunks.add(new DmiImageRagEvalRuntimeBuilder.DmiChunkRow(
                    pageChunkId,
                    knowledgeBaseId,
                    documentId,
                    logicalChunkId,
                    page.getContent() == null ? "" : page.getContent(),
                    chunkMetadata,
                    (page.getContentPath() == null ? "" : page.getContentPath()) + "\n"
                            + page.getTitle() + "\n" + page.getContent(),
                    page.getTitle(),
                    null
            ));
            chunkEmbeddingTexts.add(chunks.get(chunks.size() - 1).embeddingText());

            String pageAssetId = DmiImageRagEvalRuntimeBuilder.assetId(
                    manifestSha256, candidateCase.sourceDocumentId(), "page-" + page.getPageNumber());
            assets.add(new DmiImageRagEvalRuntimeBuilder.DmiAssetRow(
                    pageAssetId,
                    documentId,
                    "PDF_PAGE_TEXT",
                    "page-" + page.getPageNumber(),
                    page.getPageNumber(),
                    DmiImageRagEvalRuntimeBuilder.toJson(Map.of("pageNumber", page.getPageNumber())),
                    DmiImageRagEvalRuntimeBuilder.sha256(
                            page.getContent() == null ? "" : page.getContent()),
                    "pdf-text-v1",
                    "READY",
                    null
            ));
            relations.add(new DmiImageRagEvalRuntimeBuilder.DmiRelationRow(
                    pageAssetId, pageChunkId, documentId));

            String pngHash = DmiImageRagEvalRuntimeBuilder.sha256(image.pngBytes());
            String derivedImage = knowledgeBaseId + "/" + documentId + "/derived/image-" + pngHash + ".png";
            Path derivedPath = EVAL_UPLOADS.resolve(derivedImage);
            Files.createDirectories(derivedPath.getParent());
            Files.write(derivedPath, image.pngBytes());
            String imageAssetId = DmiImageRagEvalRuntimeBuilder.assetId(
                    manifestSha256, candidateCase.sourceDocumentId(), "image-1");
            Map<String, Object> imageLocator = DmiImageRagEvalRuntimeBuilder.linkedMap();
            imageLocator.put("source", "PDF");
            imageLocator.put("pageNumber", image.pageNumber());
            imageLocator.put("bbox", image.bbox());
            imageLocator.put("derivedImage", derivedImage);
            assets.add(new DmiImageRagEvalRuntimeBuilder.DmiAssetRow(
                    imageAssetId,
                    documentId,
                    "IMAGE",
                    "image-1",
                    image.pageNumber(),
                    DmiImageRagEvalRuntimeBuilder.toJson(
                            DmiImageRagEvalRuntimeBuilder.treeMap(imageLocator)),
                    pngHash,
                    "pdf-image-v1",
                    "READY",
                    derivedPath
            ));
        }

        List<float[]> embeddings = embedder.embedAll(chunkEmbeddingTexts);
        for (int i = 0; i < chunks.size(); i++) {
            DmiImageRagEvalRuntimeBuilder.DmiChunkRow chunk = chunks.get(i);
            chunks.set(i, new DmiImageRagEvalRuntimeBuilder.DmiChunkRow(
                    chunk.chunkId(), chunk.knowledgeBaseId(), chunk.documentId(), chunk.logicalChunkId(),
                    chunk.content(), chunk.metadata(), chunk.embeddingText(), chunk.titleSearchText(),
                    DmiImageRagEvalRuntimeBuilder.vectorLiteral(embeddings.get(i))
            ));
        }

        DmiImageRagEvalRuntimeBuilder.DmiImportPlan plan = new DmiImageRagEvalRuntimeBuilder.DmiImportPlan(
                knowledgeBaseId,
                evalMetadata(manifestSha256, DmiImageRagEvalRuntimeBuilder.DATASET_ID),
                List.copyOf(documents),
                List.copyOf(chunks),
                List.copyOf(assets),
                List.copyOf(relations)
        );

        DataSource dataSource = dataSource();
        try (Connection connection = DriverManager.getConnection(
                DmiImageRagEvalRuntimeBuilder.ISOLATED_JDBC_URL, "rag_eval", "")) {
            connection.setAutoCommit(false);
            DmiImageRagEvalRuntimeBuilder.importPlan(connection, OWNER_ID, plan);
            connection.commit();
        }

        SqlSessionFactory sqlSessionFactory = sqlSessionFactory(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        RagService ragService = new RagService() {
            @Override
            public float[] embed(String text) {
                return embedder.embedAll(List.of(text)).get(0);
            }

            @Override
            public List<String> similaritySearch(List<String> kbIds, String title) {
                throw new UnsupportedOperationException("dmi eval 不使用标题检索");
            }

            @Override
            public List<RagRetrievalResult> retrieve(List<String> kbIds, String query, int limit) {
                throw new UnsupportedOperationException("dmi eval 不使用普通检索");
            }

            @Override
            public List<RagRetrievalResult> retrieve(
                    List<String> kbIds, String query, com.kama.jchatmind.model.dto.RagRetrievalContext context, int limit) {
                throw new UnsupportedOperationException("dmi eval 不使用普通检索");
            }

            @Override
            public List<RagRetrievalResult> retrievePdfPageAssets(
                    List<String> kbIds, String query,
                    com.kama.jchatmind.model.dto.RagRetrievalContext context, int limit) {
                throw new UnsupportedOperationException("dmi eval 不使用页文本资产检索");
            }

            @Override
            public List<RagRetrievalResult> retrieveMarkdownTableAssets(
                    List<String> kbIds, String query,
                    com.kama.jchatmind.model.dto.RagRetrievalContext context, int limit) {
                throw new UnsupportedOperationException("dmi eval 不使用表格资产检索");
            }

            @Override
            public List<RagRetrievalResult> retrieveImageAssets(
                    List<String> kbIds, String query,
                    com.kama.jchatmind.model.dto.RagRetrievalContext context, int limit) {
                throw new UnsupportedOperationException("dmi eval 直接使用 mapper 检索");
            }

            @Override
            public Map<String, Long> indexRevisions(List<String> kbIds) {
                return Map.of();
            }

            @Override
            public String retrievalConfigurationFingerprint() {
                return "dmi-image-rag-eval";
            }
        };

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            DocumentAssetExtractionMapper extractionMapper = session.getMapper(DocumentAssetExtractionMapper.class);
            ChunkBgeM3Mapper chunkBgeM3Mapper = session.getMapper(ChunkBgeM3Mapper.class);
            DocumentAssetMapper documentAssetMapper = session.getMapper(DocumentAssetMapper.class);
            Bm25TokenDictionaryMapper tokenDictionaryMapper = session.getMapper(Bm25TokenDictionaryMapper.class);
            DocumentStorageService documentStorageService = new DocumentStorageService() {
                @Override
                public String saveFile(String kbId, String documentId,
                        org.springframework.web.multipart.MultipartFile file) {
                    throw new UnsupportedOperationException("dmi eval 不覆盖上传存储");
                }

                @Override
                public void deleteFile(String filePath) {
                    throw new UnsupportedOperationException("dmi eval 不覆盖删除存储");
                }

                @Override
                public Path getFilePath(String filePath) {
                    return EVAL_UPLOADS.resolve(filePath);
                }

                @Override
                public boolean fileExists(String filePath) {
                    return Files.exists(EVAL_UPLOADS.resolve(filePath));
                }
            };
            DeepSeekVisionClient visionClient = new DeepSeekVisionClient(
                    WebClient.builder(),
                    System.getenv("DEEPSEEK_API_KEY"),
                    "https://api.deepseek.com",
                    "deepseek-flash",
                    120);

            DocumentImageExtractionWorker worker = new DocumentImageExtractionWorker(
                    extractionMapper,
                    documentStorageService,
                    visionClient,
                    new com.kama.jchatmind.ingestion.VisualExtractionResultParser(objectMapper),
                    new com.kama.jchatmind.ingestion.VisualExtractionPersistenceService(
                            extractionMapper,
                            session.getMapper(com.kama.jchatmind.mapper.DocumentAssetEntityMapper.class),
                            session.getMapper(com.kama.jchatmind.mapper.DocumentAssetRelationMapper.class),
                            chunkBgeM3Mapper,
                            documentAssetMapper,
                            ragService,
                            new VchordBm25ProjectionService(tokenDictionaryMapper),
                            objectMapper
                    ),
                    objectMapper
            );
            org.springframework.test.util.ReflectionTestUtils.setField(worker, "enabled", true);
            ReflectionTestUtilsSetMaxAttempts(worker);
            int safetyRounds = 0;
            while (safetyRounds++ < 8) {
                int before = queryInt(dataSource,
                        "SELECT COUNT(*) FROM document_asset_extraction WHERE status IN ('PENDING', 'ACTIVE')");
                worker.processPendingImages();
                int after = queryInt(dataSource,
                        "SELECT COUNT(*) FROM document_asset_extraction WHERE status IN ('PENDING', 'ACTIVE')");
                if (before == after && after == 0) {
                    break;
                }
            }
            int activeExtractions = queryInt(dataSource,
                    "SELECT COUNT(*) FROM document_asset_extraction WHERE status = 'ACTIVE'");
            int failedExtractions = queryInt(dataSource,
                    "SELECT COUNT(*) FROM document_asset_extraction WHERE status = 'FAILED'");

            List<Map<String, Object>> caseReports = new ArrayList<>();
            List<Integer> goldAssetRanks = new ArrayList<>();
            List<Double> ocerScores = new ArrayList<>();
            List<Double> entityF1Scores = new ArrayList<>();
            List<Boolean> citationHits = new ArrayList<>();
            long evaluationStartMillis = System.currentTimeMillis();
            VisualEvidenceAnswerService answerService = new VisualEvidenceAnswerService(
                    documentStorageService,
                    visionClient,
                    objectMapper
            );
            org.springframework.test.util.ReflectionTestUtils.setField(answerService, "answerEnabled", true);

            for (DmiImageRagCandidateFixtureLoader.DmiCandidateCase candidateCase : profile.cases()) {
                Map<String, Object> caseReport = DmiImageRagEvalRuntimeBuilder.linkedMap();
                caseReport.put("caseId", candidateCase.caseId());
                caseReport.put("query", candidateCase.query());
                caseReport.put("queryType", candidateCase.queryType());
                if (!"image_evidence_lookup".equals(candidateCase.queryType())
                        && !"image_ocr_lookup".equals(candidateCase.queryType())) {
                    caseReports.add(caseReport);
                    continue;
                }
                float[] queryEmbedding = ragService.embed(candidateCase.query());
                List<RagRetrievalResult> results = chunkBgeM3Mapper.similaritySearchImageAssets(
                        List.of(knowledgeBaseId),
                        DmiImageRagEvalRuntimeBuilder.vectorLiteral(queryEmbedding),
                        null,
                        null,
                        null,
                        5
                );
                List<String> assetIds = results.stream()
                        .map(result -> {
                            try {
                                return objectMapper.readTree(result.getMetadata())
                                        .path("asset").path("id").asText();
                            } catch (Exception e) {
                                return "";
                            }
                        })
                        .toList();
                String citation = answerService.enrich(results, 2);
                String goldAssetId = DmiImageRagEvalRuntimeBuilder.assetId(
                        manifestSha256, candidateCase.sourceDocumentId(), candidateCase.goldAsset().assetKey());
                int goldRank = assetIds.indexOf(goldAssetId) + 1;
                goldAssetRanks.add(goldRank);
                caseReport.put("goldAssetId", goldAssetId);
                caseReport.put("goldAssetRank", goldRank);
                citationHits.add(citation != null && citation.contains("[assetId=" + goldAssetId + "]"));
                Map<String, Object> extractionRow = queryExtractionRow(dataSource, goldAssetId);
                if (extractionRow != null) {
                    String ocrText = (String) extractionRow.get("ocrText");
                    double ocrCer = DmiImageRagEvalMetrics.cer(candidateCase.sourceText(), ocrText);
                    ocerScores.add(ocrCer);
                    caseReport.put("ocrCer", ocrCer);
                    caseReport.put("extractedVisualKind", extractionRow.get("visualKind"));
                    List<String> extractedEntityNames = queryEntityNames(
                            dataSource, (String) extractionRow.get("extractionId"));
                    double entityF1 = DmiImageRagEvalMetrics.entityF1(
                            new java.util.HashSet<>(candidateCase.goldEntityNames()),
                            new java.util.HashSet<>(extractedEntityNames));
                    entityF1Scores.add(entityF1);
                    caseReport.put("extractedEntityNames", extractedEntityNames);
                    caseReport.put("goldEntityNames", candidateCase.goldEntityNames());
                    caseReport.put("entityF1", entityF1);
                }
                caseReport.put("retrievedAssetIds", assetIds);
                caseReport.put("citation", citation);
                caseReport.put("citationPresent",
                        citation != null && citation.contains("[assetId="));
                caseReports.add(caseReport);
            }

            Map<String, Object> report = DmiImageRagEvalRuntimeBuilder.linkedMap();
            report.put("datasetId", DmiImageRagEvalRuntimeBuilder.DATASET_ID);
            report.put("knowledgeBaseId", knowledgeBaseId);
            report.put("manifestSha256", manifestSha256);
            report.put("extractionModel", visionClient.visionModel());
            report.put("extractionPromptVersion", DeepSeekVisionClient.EXTRACTION_PROMPT_VERSION);
        report.put("activeExtractions", activeExtractions);
        Map<String, Object> metrics = DmiImageRagEvalRuntimeBuilder.linkedMap();
        metrics.put("avgOcrCer", ocerScores.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        metrics.put("macroEntityF1", entityF1Scores.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        metrics.put("retrievalHitAt5", DmiImageRagEvalMetrics.hitAtK(goldAssetRanks, 5));
        metrics.put("retrievalMrr", DmiImageRagEvalMetrics.mrr(goldAssetRanks));
        metrics.put("goldCitationPrecision", citationHits.isEmpty() ? 0 :
                (double) citationHits.stream().filter(Boolean::booleanValue).count() / citationHits.size());
        metrics.put("evaluationWallMillis", System.currentTimeMillis() - evaluationStartMillis);
        report.put("metrics", metrics);
            report.put("cases", caseReports);
            DmiImageRagEvalRuntimeBuilder.writeTextFile(REPORT_PATH, DmiImageRagEvalRuntimeBuilder.toJson(report));

            assertThat(activeExtractions).isGreaterThanOrEqualTo(2);
            assertThat(caseReports)
                    .filteredOn(item -> "image_evidence_lookup".equals(item.get("queryType"))
                            || "image_ocr_lookup".equals(item.get("queryType")))
                    .allMatch(item -> Boolean.TRUE.equals(item.get("citationPresent")));
        }
    }

    private void ReflectionTestUtilsSetMaxAttempts(DocumentImageExtractionWorker worker) {
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "maxAttempts", 2);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "timeoutSeconds", 900);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "batchSize", 1);
    }



    private int queryInt(DataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private DataSource dataSource() {
        UnpooledDataSource unpooledDataSource = new UnpooledDataSource(
                "org.postgresql.Driver",
                DmiImageRagEvalRuntimeBuilder.ISOLATED_JDBC_URL,
                "rag_eval",
                ""
        );
        return unpooledDataSource;
    }

    private Map<String, Object> queryExtractionRow(DataSource dataSource, String assetId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT extraction_id::text, ocr_text, visual_kind FROM document_asset_extraction WHERE asset_id = CAST(? AS uuid) AND status = 'ACTIVE'")) {
            statement.setString(1, assetId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("extractionId", result.getString(1));
                row.put("ocrText", result.getString(2));
                row.put("visualKind", result.getString(3));
                return row;
            }
        }
    }

    private List<String> queryEntityNames(DataSource dataSource, String extractionId) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT name FROM document_asset_entity WHERE extraction_id = CAST(? AS uuid)")) {
            statement.setString(1, extractionId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }


    private SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        Environment environment = new Environment("dmi-eval", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(new PgVectorTypeHandler());
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (String mapper : List.of(
                "mapper/ChunkBgeM3Mapper.xml",
                "mapper/DocumentAssetMapper.xml",
                "mapper/DocumentAssetExtractionMapper.xml",
                "mapper/DocumentAssetEntityMapper.xml",
                "mapper/DocumentAssetRelationMapper.xml",
                "mapper/Bm25TokenDictionaryMapper.xml"
        )) {
            try (Reader reader = new InputStreamReader(
                    resolver.getResource("classpath:" + mapper).getInputStream(), StandardCharsets.UTF_8)) {
                new XMLMapperBuilder(reader, configuration, mapper, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private Map<String, Object> evalMetadata(String manifestSha256, String sourceDocumentId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evaluationNamespace", "rag-eval");
        metadata.put("datasetId", DmiImageRagEvalRuntimeBuilder.DATASET_ID);
        metadata.put("manifestSha256", manifestSha256);
        metadata.put("candidateOnly", true);
        metadata.put("sourceDocumentId", sourceDocumentId);
        return metadata;
    }

    private boolean StringUtilsHasText(String value) {
        return value != null && !value.isBlank();
    }
}
