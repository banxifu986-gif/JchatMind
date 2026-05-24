package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.QueryRewriteService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.DocumentStorageServiceImpl;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import com.kama.jchatmind.service.impl.RetrievableTitleLexicalizer;
import com.kama.jchatmind.util.RagChunkSupport;
import org.junit.jupiter.api.Assumptions;
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
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.multipart.MultipartFile;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SpringBootTest(classes = RagRecallEvaluationTest.RagEvalTestConfig.class)
@ActiveProfiles("rag-eval")
class RagRecallEvaluationTest {

    private static final String FIXTURE_KB_NAME = "RAG Recall Fixture KB";
    private static final String MODE_FIXTURE = "fixture";
    private static final String MODE_REAL = "real";
    private static final String MODE_BOTH = "both";
    private static final String QUERY_STYLE_TITLE = "title_exact";
    private static final String QUERY_STYLE_TITLE_PATH = "title_path";
    private static final String QUERY_STYLE_TITLE_TO_CONTENT = "title_to_content";
    private static final String QUERY_STYLE_SOURCE_SCOPED_TITLE = "source_scoped_title";
    private static final String QUERY_STYLE_CONTEXTUAL_TITLE_QUERY = "contextual_title_query";
    private static final String QUERY_STYLE_AUTO_PATH_SELECTION = "auto_path_selection";
    private static final String QUERY_STYLE_REWRITE = "content_rewrite";
    private static final String QUERY_STYLE_USER_LIKE_QUESTION = "user_like_question";
    private static final String QUERY_STYLE_FOLLOW_UP_CONTEXTUAL_REWRITE = "follow_up_contextual_rewrite";
    private static final String QUERY_STYLE_TOPIC_SWITCH_GUARD = "topic_switch_guard";
    private static final String DIMENSION_TITLE_RECALL = "title_recall";
    private static final String DIMENSION_QUERY_REWRITE = "query_rewrite";
    private static final String DIMENSION_RERANK_QUALITY = "rerank_quality";
    private static final String DIMENSION_FOLLOW_UP_CONTEXTUAL = "follow_up_contextual";
    private static final String VARIANT_FULL_CHAIN = "full_chain";
    private static final String VARIANT_NO_QUERY_EXPANSION = "no_query_expansion";
    private static final String VARIANT_NO_RERANK = "no_rerank";
    private static final int EVAL_RETRIEVAL_LIMIT = 10;
    private static final int REPORT_CASE_LIMIT = 10;
    private static final int DIFF_CASE_LIMIT = 10;
    private static final String EXCLUDED_EMPTY_QUERY = "empty_query";
    private static final String EXCLUDED_EMPTY_REWRITE_QUERY = "empty_rewrite_query";
    private static final String EXCLUDED_MISSING_GOLD_CHUNK = "missing_gold_chunk";
    private static final String SKIPPED_MISSING_FILE_OR_METADATA = "missing_file_or_metadata";
    private static final String SKIPPED_NON_MARKDOWN = "non_markdown";
    private static final String GOLD_EXACT_CONTENT = "exact_content";
    private static final String GOLD_TITLE_ANCHOR = "title_anchor";
    private static final String GOLD_METADATA_SECTION_INDEX = "metadata_section_index";
    private static final String GOLD_SECTION_ORDER_EXACT = "section_order_exact";
    private static final String GOLD_CONTENT_OVERLAP = "content_overlap";
    private static final String GOLD_NOT_FOUND = "not_found";

    @Autowired
    private RagService ragService;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private DocumentConverter documentConverter;

    @Autowired
    private KnowledgeBaseConverter knowledgeBaseConverter;

    @Autowired
    private DocumentStorageService documentStorageService;

    @Autowired
    private MarkdownParserService markdownParserService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${rag.eval.mode:fixture}")
    private String evalMode;

    @Value("${rag.eval.real-kb-id:}")
    private String realKbId;

    @Value("${rag.eval.assert:false}")
    private boolean assertEnabled;

    @Value("${rag.eval.min-recall-at-1:0}")
    private double minRecallAt1;

    @Value("${rag.eval.min-recall-at-3:0}")
    private double minRecallAt3;

    @Value("${rag.eval.min-recall-at-5:0}")
    private double minRecallAt5;

    @Value("${rag.eval.compare-with:}")
    private String compareWithReportPath;

    @Value("${rag.eval.enable-ab-comparison:true}")
    private boolean enableAbComparison;

    @Value("${rag.eval.ab-sample-size:20}")
    private int abSampleSize;

    @Value("${rag.eval.ensure-pg-trgm:true}")
    private boolean ensurePgTrgm;

    @Value("${rag.eval.rebuild-real-kb:false}")
    private boolean rebuildRealKb;

    @Value("${rag.eval.real-max-documents:0}")
    private int realMaxDocuments;

    @Value("${rag.eval.real-max-cases:0}")
    private int realMaxCases;

    @Value("${rag.eval.real-document-order:created_desc}")
    private String realDocumentOrder;

    @Value("${ollama.base-url}")
    private String ollamaBaseUrl;

    @Value("${ollama.embedding-model}")
    private String embeddingModel;

    @Value("classpath:rag-eval/fixtures/fixture-kb.md")
    private Resource fixtureMarkdown;

    @Value("${document.storage.base-path}")
    private String documentStorageBasePath;

    private Path reportOutputPath;

    @BeforeEach
    void setUp() throws IOException {
        reportOutputPath = Path.of("target", "rag-eval", "report.json");
        Files.createDirectories(reportOutputPath.getParent());
        Files.createDirectories(Path.of(documentStorageBasePath));
        ensurePgTrgmReady();
    }

    private void ensurePgTrgmReady() {
        if (!ensurePgTrgm) {
            return;
        }
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_chunk_retrievable_title_trgm
                    ON chunk_bge_m3
                    USING gin ((lower(regexp_replace(trim(COALESCE(metadata->>'retrievableTitle', metadata->>'title')), '\\s+', ' ', 'g'))) gin_trgm_ops)
                    """);
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_chunk_retrievable_title_search_tsv
                    ON chunk_bge_m3
                    USING gin (to_tsvector('simple', COALESCE(metadata->>'retrievableTitleSearchText', '')))
                    """);
            jdbcTemplate.queryForObject("SELECT similarity('jchatmind', 'jchatmind')", Double.class);
        } catch (Exception e) {
            throw new IllegalStateException("评测库 pg_trgm 初始化失败，请确认当前数据库用户有 CREATE EXTENSION 权限", e);
        }
    }

    @Test
    void evaluateRecall() throws Exception {
        List<EvaluationSummary> summaries = new ArrayList<>();
        Map<String, Set<String>> previousMissCaseIds = loadPreviousMissCaseIds();

        if (shouldRunFixture()) {
            summaries.add(runFixtureEvaluation(previousMissCaseIds));
        }

        if (shouldRunReal()) {
            Assumptions.assumeTrue(StringUtils.hasText(realKbId), "缺少 rag.eval.real-kb-id，跳过真实知识库评测");
            summaries.add(runRealEvaluation(realKbId, previousMissCaseIds));
        }

        String jsonReport = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summaries);
        Files.writeString(reportOutputPath, jsonReport);
        System.out.println(jsonReport);

        if (assertEnabled) {
            for (EvaluationSummary summary : summaries) {
                assertThreshold(summary.recallAt1, minRecallAt1, summary.group + " Recall@1");
                assertThreshold(summary.recallAt3, minRecallAt3, summary.group + " Recall@3");
                assertThreshold(summary.recallAt5, minRecallAt5, summary.group + " Recall@5");
            }
        }
    }

    private EvaluationSummary runFixtureEvaluation(Map<String, Set<String>> previousMissCaseIds) throws Exception {
        cleanupFixtureData();

        KnowledgeBase knowledgeBase = createKnowledgeBase(FIXTURE_KB_NAME, "离线召回测试知识库");
        Document document = ingestFixtureMarkdown(knowledgeBase.getId(), fixtureMarkdown);
        List<ChunkBgeM3> persistedChunks = chunkBgeM3Mapper.selectByDocId(document.getId());
        List<MarkdownParserService.MarkdownSection> sections = parseMarkdownByDocument(document);

        List<QueryCase> queryCases = buildQueryCases(
                knowledgeBase.getId(),
                document.getId(),
                sections,
                persistedChunks,
                "fixture"
        );
        return evaluateCases("fixture", queryCases, Map.of(), previousMissCaseIds);
    }

    private EvaluationSummary runRealEvaluation(String kbId, Map<String, Set<String>> previousMissCaseIds) throws Exception {
        List<QueryCase> queryCases = new ArrayList<>();
        Map<String, Integer> skippedDocumentReasons = new LinkedHashMap<>();
        List<Document> documents = rebuildRealKb
                ? rebuildRealKnowledgeBase(kbId)
                : documentMapper.selectByKbId(kbId);
        List<Document> scopedDocuments = scopeRealDocuments(documents);

        for (Document document : scopedDocuments) {
            if (!isMarkdown(document.getFiletype())) {
                addCount(skippedDocumentReasons, SKIPPED_NON_MARKDOWN);
                continue;
            }
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() == null || !StringUtils.hasText(documentDTO.getMetadata().getFilePath())) {
                addCount(skippedDocumentReasons, SKIPPED_MISSING_FILE_OR_METADATA);
                continue;
            }
            if (!documentStorageService.fileExists(documentDTO.getMetadata().getFilePath())) {
                addCount(skippedDocumentReasons, SKIPPED_MISSING_FILE_OR_METADATA);
                continue;
            }

            List<MarkdownParserService.MarkdownSection> sections = parseMarkdownByDocument(document);
            List<ChunkBgeM3> persistedChunks = chunkBgeM3Mapper.selectByDocId(document.getId());
            queryCases.addAll(buildQueryCases(kbId, document.getId(), sections, persistedChunks, "real"));
            if (realMaxCases > 0 && queryCases.size() >= realMaxCases) {
                queryCases = new ArrayList<>(queryCases.subList(0, realMaxCases));
                break;
            }
        }

        return evaluateCases("real", queryCases, skippedDocumentReasons, previousMissCaseIds);
    }

    private List<Document> scopeRealDocuments(List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }

        List<Document> scoped = new ArrayList<>(documents);
        Comparator<Document> comparator = Comparator.comparing(Document::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(Document::getId, Comparator.nullsLast(String::compareTo));
        if (!"created_asc".equalsIgnoreCase(realDocumentOrder)) {
            comparator = comparator.reversed();
        }
        scoped.sort(comparator);

        if (realMaxDocuments > 0 && scoped.size() > realMaxDocuments) {
            return new ArrayList<>(scoped.subList(0, realMaxDocuments));
        }
        return scoped;
    }

    private List<Document> rebuildRealKnowledgeBase(String kbId) throws Exception {
        List<Document> existingDocuments = documentMapper.selectByKbId(kbId);
        List<RealDocumentSnapshot> snapshots = new ArrayList<>();
        for (Document document : existingDocuments) {
            if (!isMarkdown(document.getFiletype())) {
                continue;
            }
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() == null || !StringUtils.hasText(documentDTO.getMetadata().getFilePath())) {
                continue;
            }
            if (!documentStorageService.fileExists(documentDTO.getMetadata().getFilePath())) {
                continue;
            }
            Path sourcePath = documentStorageService.getFilePath(documentDTO.getMetadata().getFilePath());
            snapshots.add(new RealDocumentSnapshot(
                    document.getFilename(),
                    document.getFiletype(),
                    Files.readAllBytes(sourcePath)
            ));
        }

        cleanupKnowledgeBaseDocuments(kbId);

        List<Document> rebuiltDocuments = new ArrayList<>();
        for (RealDocumentSnapshot snapshot : snapshots) {
            String documentId = insertDocumentRecord(kbId, snapshot.filename(), snapshot.bytes().length, snapshot.filetype());
            MultipartFile multipartFile = new InMemoryMultipartFile(snapshot.filename(), "text/markdown", snapshot.bytes());
            String filePath = documentStorageService.saveFile(kbId, documentId, multipartFile);
            updateDocumentMetadata(documentId, kbId, snapshot.filename(), snapshot.bytes().length, filePath, snapshot.filetype());
            createChunksFromMarkdown(kbId, documentId, filePath, snapshot.filename(), snapshot.filetype());
            rebuiltDocuments.add(documentMapper.selectById(documentId));
        }
        return rebuiltDocuments;
    }

    private EvaluationSummary evaluateCases(
            String source,
            List<QueryCase> cases,
            Map<String, Integer> skippedDocumentReasons,
            Map<String, Set<String>> previousMissCaseIds
    ) {
        return evaluateCases(source, cases, skippedDocumentReasons, previousMissCaseIds, ragService, true);
    }

    private EvaluationSummary evaluateCases(
            String source,
            List<QueryCase> cases,
            Map<String, Integer> skippedDocumentReasons,
            Map<String, Set<String>> previousMissCaseIds,
            RagService retrievalService,
            boolean includeVariantComparisons
    ) {
        Map<String, List<QueryCase>> caseGroups = cases.stream()
                .collect(Collectors.groupingBy(QueryCase::queryStyle, LinkedHashMap::new, Collectors.toList()));

        List<EvaluationSummary> primaryBreakdown = new ArrayList<>();
        for (String queryStyle : List.of(QUERY_STYLE_TITLE, QUERY_STYLE_REWRITE)) {
            primaryBreakdown.add(evaluateStyleGroup(
                    source + "/" + queryStyle,
                    caseGroups.getOrDefault(queryStyle, List.of()),
                    previousMissCaseIds,
                    retrievalService
            ));
        }

        List<EvaluationSummary> diagnosticBreakdown = new ArrayList<>();
        for (String queryStyle : List.of(
                QUERY_STYLE_TITLE_TO_CONTENT,
                QUERY_STYLE_SOURCE_SCOPED_TITLE,
                QUERY_STYLE_CONTEXTUAL_TITLE_QUERY,
                QUERY_STYLE_AUTO_PATH_SELECTION,
                QUERY_STYLE_TITLE_PATH,
                QUERY_STYLE_USER_LIKE_QUESTION,
                QUERY_STYLE_FOLLOW_UP_CONTEXTUAL_REWRITE,
                QUERY_STYLE_TOPIC_SWITCH_GUARD
        )) {
            diagnosticBreakdown.add(evaluateStyleGroup(
                    source + "/" + queryStyle,
                    caseGroups.getOrDefault(queryStyle, List.of()),
                    previousMissCaseIds,
                    retrievalService
            ));
        }

        List<EvaluationSummary> allBreakdown = new ArrayList<>();
        allBreakdown.addAll(primaryBreakdown);
        allBreakdown.addAll(diagnosticBreakdown);

        int total = primaryBreakdown.stream().mapToInt(EvaluationSummary::total).sum();
        int evaluated = primaryBreakdown.stream().mapToInt(EvaluationSummary::evaluated).sum();
        int excluded = primaryBreakdown.stream().mapToInt(EvaluationSummary::excluded).sum();
        double coverage = ratio(evaluated, total);
        double recallAt1 = average(primaryBreakdown.stream().map(EvaluationSummary::recallAt1).toList());
        double recallAt3 = average(primaryBreakdown.stream().map(EvaluationSummary::recallAt3).toList());
        double recallAt5 = average(primaryBreakdown.stream().map(EvaluationSummary::recallAt5).toList());
        double recallAt10 = average(primaryBreakdown.stream().map(EvaluationSummary::recallAt10).toList());
        int hitAt1Count = primaryBreakdown.stream().mapToInt(EvaluationSummary::hitAt1Count).sum();
        int hitAt3Count = primaryBreakdown.stream().mapToInt(EvaluationSummary::hitAt3Count).sum();
        int hitAt5Count = primaryBreakdown.stream().mapToInt(EvaluationSummary::hitAt5Count).sum();
        int hitAt10Count = primaryBreakdown.stream().mapToInt(EvaluationSummary::hitAt10Count).sum();
        double weightedRecallAt1 = ratio(hitAt1Count, evaluated);
        double weightedRecallAt3 = ratio(hitAt3Count, evaluated);
        double weightedRecallAt5 = ratio(hitAt5Count, evaluated);
        double weightedRecallAt10 = ratio(hitAt10Count, evaluated);
        double mrrAt3 = weightedAverage(primaryBreakdown, 3);
        double mrrAt10 = weightedAverage(primaryBreakdown, 10);
        HitDistribution hitDistribution = mergeHitDistributions(primaryBreakdown);
        Map<String, Integer> excludedReasons = mergeExcludedReasons(primaryBreakdown);
        List<String> missCases = primaryBreakdown.stream()
                .flatMap(summary -> summary.missCases().stream())
                .limit(REPORT_CASE_LIMIT)
                .toList();
        List<String> missCaseIds = primaryBreakdown.stream()
                .flatMap(summary -> summary.missCaseIds().stream())
                .toList();
        List<String> newMissCases = newMissCases(source, missCaseIds, previousMissCaseIds);
        List<String> fixedMissCases = fixedMissCases(source, missCaseIds, previousMissCaseIds);
        List<DimensionSummary> dimensions = buildDimensionSummaries(source, allBreakdown);
        List<VariantComparison> comparisons = includeVariantComparisons
                ? buildVariantComparisons(source, cases, skippedDocumentReasons, primaryBreakdown, diagnosticBreakdown)
                : List.of();

        return new EvaluationSummary(
                source,
                total,
                evaluated,
                excluded,
                coverage,
                recallAt1,
                recallAt3,
                recallAt5,
                recallAt10,
                weightedRecallAt1,
                weightedRecallAt3,
                weightedRecallAt5,
                weightedRecallAt10,
                hitAt1Count,
                hitAt3Count,
                hitAt5Count,
                hitAt10Count,
                mrrAt3,
                mrrAt10,
                hitDistribution,
                excludedReasons,
                skippedDocumentReasons,
                missCases,
                missCaseIds,
                newMissCases,
                fixedMissCases,
                dimensions,
                allBreakdown,
                comparisons
        );
    }

    private EvaluationSummary evaluateStyleGroup(
            String group,
            List<QueryCase> cases,
            Map<String, Set<String>> previousMissCaseIds,
            RagService retrievalService
    ) {
        List<EvaluatedCase> evaluatedCases = new ArrayList<>();
        Map<String, Integer> excludedReasons = new LinkedHashMap<>();

        for (QueryCase queryCase : cases) {
            if (!queryCase.evaluable()) {
                addCount(excludedReasons, queryCase.excludedReason());
                continue;
            }

            List<RagRetrievalResult> results = retrieveForEvaluation(queryCase, retrievalService);
            List<String> topChunkIds = results.stream()
                    .map(RagRetrievalResult::getChunkId)
                    .toList();
            evaluatedCases.add(new EvaluatedCase(
                    queryCase.caseId(),
                    queryCase.goldChunkIds(),
                    queryCase.goldResolutionMode(),
                    queryCase.goldCandidateCount(),
                    topChunkIds
            ));
        }

        int total = cases.size();
        int evaluated = evaluatedCases.size();
        int excluded = total - evaluated;
        double coverage = ratio(evaluated, total);
        double recallAt1 = hitRate(evaluatedCases, 1);
        double recallAt3 = hitRate(evaluatedCases, 3);
        double recallAt5 = hitRate(evaluatedCases, 5);
        double recallAt10 = hitRate(evaluatedCases, 10);
        int hitAt1Count = hitCount(evaluatedCases, 1);
        int hitAt3Count = hitCount(evaluatedCases, 3);
        int hitAt5Count = hitCount(evaluatedCases, 5);
        int hitAt10Count = hitCount(evaluatedCases, 10);
        double mrrAt3 = mrrAt(evaluatedCases, 3);
        double mrrAt10 = mrrAt(evaluatedCases, 10);
        HitDistribution hitDistribution = hitDistribution(evaluatedCases);
        List<String> missCases = evaluatedCases.stream()
                .filter(item -> !item.hitAt(5))
                .map(item -> item.caseId()
                        + " | gold=" + item.goldChunkIds()
                        + " | goldMode=" + item.goldResolutionMode()
                        + " | top=" + item.topChunkIds())
                .limit(REPORT_CASE_LIMIT)
                .toList();
        List<String> missCaseIds = evaluatedCases.stream()
                .filter(item -> !item.hitAt(5))
                .map(EvaluatedCase::caseId)
                .toList();
        List<String> newMissCases = newMissCases(group, missCaseIds, previousMissCaseIds);
        List<String> fixedMissCases = fixedMissCases(group, missCaseIds, previousMissCaseIds);

        return new EvaluationSummary(
                group,
                total,
                evaluated,
                excluded,
                coverage,
                recallAt1,
                recallAt3,
                recallAt5,
                recallAt10,
                recallAt1,
                recallAt3,
                recallAt5,
                recallAt10,
                hitAt1Count,
                hitAt3Count,
                hitAt5Count,
                hitAt10Count,
                mrrAt3,
                mrrAt10,
                hitDistribution,
                excludedReasons,
                Map.of(),
                missCases,
                missCaseIds,
                newMissCases,
                fixedMissCases,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private List<VariantComparison> buildVariantComparisons(
            String source,
            List<QueryCase> cases,
            Map<String, Integer> skippedDocumentReasons,
            List<EvaluationSummary> primaryBreakdown,
            List<EvaluationSummary> diagnosticBreakdown
    ) {
        if (!enableAbComparison) {
            return List.of();
        }

        List<QueryCase> sampledCases = sampleCasesForAb(cases);
        if (sampledCases.isEmpty()) {
            return List.of();
        }

        EvaluationSummary baseline = evaluateCases(
                source + "#ab_sample",
                sampledCases,
                skippedDocumentReasons,
                Map.of(),
                ragService,
                false
        );
        EvaluationSummary noQueryExpansion = evaluateCases(
                source + "#ab_sample",
                sampledCases,
                skippedDocumentReasons,
                Map.of(),
                buildVariantRagService(true, false),
                false
        );
        EvaluationSummary noRerank = evaluateCases(
                source + "#ab_sample",
                sampledCases,
                skippedDocumentReasons,
                Map.of(),
                buildVariantRagService(false, true),
                false
        );

        return List.of(
                buildVariantComparison(
                        VARIANT_NO_QUERY_EXPANSION,
                        "关闭 retrievalQueries 扩展，仅保留原始 query，观察 query rewrite / follow-up 补全贡献",
                        baseline,
                        noQueryExpansion
                ),
                buildVariantComparison(
                        VARIANT_NO_RERANK,
                        "关闭 rerank，仅保留候选合并后的原始顺序，观察排序层贡献",
                        baseline,
                        noRerank
                ),
                buildBottleneckAssessment(source, baseline, noQueryExpansion, noRerank, primaryBreakdown, diagnosticBreakdown)
        );
    }

    private VariantComparison buildVariantComparison(
            String variant,
            String description,
            EvaluationSummary baseline,
            EvaluationSummary compared
    ) {
        List<VariantComparisonItem> items = new ArrayList<>();
        items.add(buildVariantComparisonItem("overall", baseline, compared));

        Map<String, EvaluationSummary> baselineBreakdown = baseline.breakdown().stream()
                .collect(Collectors.toMap(EvaluationSummary::group, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, EvaluationSummary> comparedBreakdown = compared.breakdown().stream()
                .collect(Collectors.toMap(EvaluationSummary::group, item -> item, (left, right) -> left, LinkedHashMap::new));

        for (String group : List.of(
                baseline.group() + "/" + QUERY_STYLE_TITLE,
                baseline.group() + "/" + QUERY_STYLE_REWRITE,
                baseline.group() + "/" + QUERY_STYLE_USER_LIKE_QUESTION,
                baseline.group() + "/" + QUERY_STYLE_FOLLOW_UP_CONTEXTUAL_REWRITE
        )) {
            EvaluationSummary baselineGroup = baselineBreakdown.get(group);
            EvaluationSummary comparedGroup = comparedBreakdown.get(group);
            if (baselineGroup == null || comparedGroup == null || baselineGroup.total() == 0) {
                continue;
            }
            items.add(buildVariantComparisonItem(group, baselineGroup, comparedGroup));
        }

        Map<String, DimensionSummary> baselineDimensions = baseline.dimensions().stream()
                .collect(Collectors.toMap(DimensionSummary::name, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, DimensionSummary> comparedDimensions = compared.dimensions().stream()
                .collect(Collectors.toMap(DimensionSummary::name, item -> item, (left, right) -> left, LinkedHashMap::new));
        for (String dimensionName : List.of(
                DIMENSION_QUERY_REWRITE,
                DIMENSION_RERANK_QUALITY,
                DIMENSION_FOLLOW_UP_CONTEXTUAL
        )) {
            DimensionSummary baselineDimension = baselineDimensions.get(dimensionName);
            DimensionSummary comparedDimension = comparedDimensions.get(dimensionName);
            if (baselineDimension == null || comparedDimension == null || baselineDimension.total() == 0) {
                continue;
            }
            items.add(buildVariantComparisonItem("dimension/" + dimensionName, baselineDimension, comparedDimension));
        }

        return new VariantComparison(variant, description, items);
    }

    private List<QueryCase> sampleCasesForAb(List<QueryCase> cases) {
        List<String> diagnosticStyles = List.of(
                QUERY_STYLE_TITLE,
                QUERY_STYLE_REWRITE,
                QUERY_STYLE_USER_LIKE_QUESTION,
                QUERY_STYLE_FOLLOW_UP_CONTEXTUAL_REWRITE,
                QUERY_STYLE_TOPIC_SWITCH_GUARD,
                QUERY_STYLE_AUTO_PATH_SELECTION
        );
        List<QueryCase> sampled = new ArrayList<>();
        for (String queryStyle : diagnosticStyles) {
            sampled.addAll(cases.stream()
                    .filter(QueryCase::evaluable)
                    .filter(item -> queryStyle.equals(item.queryStyle()))
                    .limit(abSampleSize)
                    .toList());
        }
        return sampled;
    }

    private VariantComparison buildBottleneckAssessment(
            String source,
            EvaluationSummary baseline,
            EvaluationSummary noQueryExpansion,
            EvaluationSummary noRerank,
            List<EvaluationSummary> primaryBreakdown,
            List<EvaluationSummary> diagnosticBreakdown
    ) {
        double queryExpansionImpact = baseline.mrrAt3() - noQueryExpansion.mrrAt3();
        double rerankImpact = baseline.mrrAt3() - noRerank.mrrAt3();
        String dominantLayer;
        if (queryExpansionImpact > rerankImpact + 0.02D) {
            dominantLayer = "query_expansion_dominant";
        } else if (rerankImpact > queryExpansionImpact + 0.02D) {
            dominantLayer = "rerank_dominant";
        } else {
            dominantLayer = "mixed_or_close";
        }

        List<VariantComparisonItem> items = List.of(
                new VariantComparisonItem(
                        source + "#ab_sample/overall_vs_no_query_expansion",
                        baseline.recallAt1(),
                        baseline.recallAt5(),
                        baseline.mrrAt3(),
                        noQueryExpansion.recallAt1(),
                        noQueryExpansion.recallAt5(),
                        noQueryExpansion.mrrAt3(),
                        noQueryExpansion.recallAt1() - baseline.recallAt1(),
                        noQueryExpansion.recallAt5() - baseline.recallAt5(),
                        noQueryExpansion.mrrAt3() - baseline.mrrAt3()
                ),
                new VariantComparisonItem(
                        source + "#ab_sample/overall_vs_no_rerank",
                        baseline.recallAt1(),
                        baseline.recallAt5(),
                        baseline.mrrAt3(),
                        noRerank.recallAt1(),
                        noRerank.recallAt5(),
                        noRerank.mrrAt3(),
                        noRerank.recallAt1() - baseline.recallAt1(),
                        noRerank.recallAt5() - baseline.recallAt5(),
                        noRerank.mrrAt3() - baseline.mrrAt3()
                )
        );

        String description = "sampleSize=" + abSampleSize
                + ", dominantLayer=" + dominantLayer
                + ", queryExpansionMrrImpact=" + formatDelta(queryExpansionImpact)
                + ", rerankMrrImpact=" + formatDelta(rerankImpact)
                + ", primaryGroups=" + primaryBreakdown.stream().map(EvaluationSummary::group).toList()
                + ", diagnosticGroups=" + diagnosticBreakdown.stream()
                .map(EvaluationSummary::group)
                .filter(group -> group.endsWith("/" + QUERY_STYLE_USER_LIKE_QUESTION)
                        || group.endsWith("/" + QUERY_STYLE_FOLLOW_UP_CONTEXTUAL_REWRITE)
                        || group.endsWith("/" + QUERY_STYLE_TOPIC_SWITCH_GUARD)
                        || group.endsWith("/" + QUERY_STYLE_AUTO_PATH_SELECTION))
                .toList();
        return new VariantComparison("bottleneck_assessment", description, items);
    }

    private VariantComparisonItem buildVariantComparisonItem(
            String group,
            EvaluationSummary baseline,
            EvaluationSummary compared
    ) {
        return new VariantComparisonItem(
                group,
                baseline.recallAt1(),
                baseline.recallAt5(),
                baseline.mrrAt3(),
                compared.recallAt1(),
                compared.recallAt5(),
                compared.mrrAt3(),
                compared.recallAt1() - baseline.recallAt1(),
                compared.recallAt5() - baseline.recallAt5(),
                compared.mrrAt3() - baseline.mrrAt3()
        );
    }

    private VariantComparisonItem buildVariantComparisonItem(
            String group,
            DimensionSummary baseline,
            DimensionSummary compared
    ) {
        return new VariantComparisonItem(
                group,
                baseline.recallAt1(),
                baseline.recallAt5(),
                baseline.mrrAt3(),
                compared.recallAt1(),
                compared.recallAt5(),
                compared.mrrAt3(),
                compared.recallAt1() - baseline.recallAt1(),
                compared.recallAt5() - baseline.recallAt5(),
                compared.mrrAt3() - baseline.mrrAt3()
        );
    }

    private List<DimensionSummary> buildDimensionSummaries(String source, List<EvaluationSummary> breakdown) {
        Map<String, EvaluationSummary> summaryByGroup = breakdown.stream()
                .collect(Collectors.toMap(EvaluationSummary::group, item -> item, (left, right) -> left, LinkedHashMap::new));

        List<DimensionSummary> dimensions = new ArrayList<>();
        dimensions.add(buildDimensionSummary(
                DIMENSION_TITLE_RECALL,
                "关注标题、路径和上下文定位能力",
                List.of(
                        source + "/" + QUERY_STYLE_TITLE,
                        source + "/" + QUERY_STYLE_TITLE_PATH,
                        source + "/" + QUERY_STYLE_SOURCE_SCOPED_TITLE,
                        source + "/" + QUERY_STYLE_CONTEXTUAL_TITLE_QUERY,
                        source + "/" + QUERY_STYLE_AUTO_PATH_SELECTION
                ),
                summaryByGroup
        ));
        dimensions.add(buildDimensionSummary(
                DIMENSION_QUERY_REWRITE,
                "关注自然问法和内容改写后的召回能力",
                List.of(
                        source + "/" + QUERY_STYLE_REWRITE,
                        source + "/" + QUERY_STYLE_USER_LIKE_QUESTION
                ),
                summaryByGroup
        ));
        dimensions.add(buildDimensionSummary(
                DIMENSION_RERANK_QUALITY,
                "关注排序质量，优先看 Recall@1 和 MRR",
                List.of(
                        source + "/" + QUERY_STYLE_TITLE,
                        source + "/" + QUERY_STYLE_REWRITE,
                        source + "/" + QUERY_STYLE_TITLE_PATH,
                        source + "/" + QUERY_STYLE_USER_LIKE_QUESTION
                ),
                summaryByGroup
        ));
        dimensions.add(buildDimensionSummary(
                DIMENSION_FOLLOW_UP_CONTEXTUAL,
                "关注低信息追问和带上下文的主题切换",
                List.of(
                        source + "/" + QUERY_STYLE_FOLLOW_UP_CONTEXTUAL_REWRITE,
                        source + "/" + QUERY_STYLE_TOPIC_SWITCH_GUARD
                ),
                summaryByGroup
        ));
        return dimensions.stream()
                .filter(item -> item.total() > 0)
                .toList();
    }

    private DimensionSummary buildDimensionSummary(
            String name,
            String description,
            List<String> groups,
            Map<String, EvaluationSummary> summaryByGroup
    ) {
        List<EvaluationSummary> matched = groups.stream()
                .map(summaryByGroup::get)
                .filter(item -> item != null && item.total() > 0)
                .toList();
        int total = matched.stream().mapToInt(EvaluationSummary::total).sum();
        int evaluated = matched.stream().mapToInt(EvaluationSummary::evaluated).sum();
        int excluded = matched.stream().mapToInt(EvaluationSummary::excluded).sum();
        int hitAt1Count = matched.stream().mapToInt(EvaluationSummary::hitAt1Count).sum();
        int hitAt3Count = matched.stream().mapToInt(EvaluationSummary::hitAt3Count).sum();
        int hitAt5Count = matched.stream().mapToInt(EvaluationSummary::hitAt5Count).sum();
        int hitAt10Count = matched.stream().mapToInt(EvaluationSummary::hitAt10Count).sum();
        return new DimensionSummary(
                name,
                description,
                matched.stream().map(EvaluationSummary::group).toList(),
                total,
                evaluated,
                excluded,
                ratio(evaluated, total),
                ratio(hitAt1Count, evaluated),
                ratio(hitAt3Count, evaluated),
                ratio(hitAt5Count, evaluated),
                ratio(hitAt10Count, evaluated),
                weightedAverage(matched, 3),
                weightedAverage(matched, 10)
        );
    }

    private RagService buildVariantRagService(boolean disableQueryExpansion, boolean disableRerank) {
        return new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                queryRewriteService,
                ollamaBaseUrl,
                embeddingModel,
                false,
                disableQueryExpansion,
                disableRerank,
                2048
        );
    }

    private List<RagRetrievalResult> retrieveForEvaluation(QueryCase queryCase, RagService retrievalService) {
        return retrievalService.retrieve(List.of(queryCase.kbId()), queryCase.query(), queryCase.context(), EVAL_RETRIEVAL_LIMIT);
    }

    private List<QueryCase> buildQueryCases(
            String kbId,
            String docId,
            List<MarkdownParserService.MarkdownSection> sections,
            List<ChunkBgeM3> persistedChunks,
            String source
    ) {
        List<QueryCase> queryCases = new ArrayList<>();
        List<ChunkBgeM3> sortedChunks = persistedChunks.stream()
                .sorted(Comparator.comparing(ChunkBgeM3::getCreatedAt))
                .toList();

        for (int i = 0; i < sections.size(); i++) {
            MarkdownParserService.MarkdownSection section = sections.get(i);
            GoldResolution contentGoldResolution = resolveGoldChunks(docId, section, sortedChunks, i);
            GoldResolution titleGoldResolution = resolveTitleAnchorGoldChunks(docId, section, sortedChunks);
            String sourceName = extractSourceName(contentGoldResolution, sortedChunks);
            String sourceType = extractSourceType(contentGoldResolution, sortedChunks);
            String contentPath = section.getContentPath();
            String parentContentPath = parentContentPath(contentPath);

            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_TITLE,
                    kbId,
                    docId,
                    i,
                    section.getTitle(),
                    titleGoldResolution
            ));

            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_TITLE_TO_CONTENT,
                    kbId,
                    docId,
                    i,
                    section.getTitle(),
                    contentGoldResolution
            ));

            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_SOURCE_SCOPED_TITLE,
                    kbId,
                    docId,
                    i,
                    section.getTitle(),
                    RagRetrievalContext.builder()
                            .sourceName(sourceName)
                            .sourceType(sourceType)
                            .build(),
                    contentGoldResolution
            ));

            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_CONTEXTUAL_TITLE_QUERY,
                    kbId,
                    docId,
                    i,
                    section.getTitle(),
                    RagRetrievalContext.builder()
                            .sourceName(sourceName)
                            .sourceType(sourceType)
                            .contentPath(parentContentPath)
                            .build(),
                    contentGoldResolution
            ));

            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_AUTO_PATH_SELECTION,
                    kbId,
                    docId,
                    i,
                    buildPathAwareTitleQuery(parentContentPath, section.getTitle()),
                    contentGoldResolution
            ));

            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_TITLE_PATH,
                    kbId,
                    docId,
                    i,
                    section.getContentPath(),
                    contentGoldResolution
            ));

            String rewriteQuery = buildRewriteQuery(section.getContent());
            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_REWRITE,
                    kbId,
                    docId,
                    i,
                    rewriteQuery,
                    contentGoldResolution
            ));

            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_USER_LIKE_QUESTION,
                    kbId,
                    docId,
                    i,
                    buildUserLikeQuestion(section),
                    contentGoldResolution
            ));

            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_FOLLOW_UP_CONTEXTUAL_REWRITE,
                    kbId,
                    docId,
                    i,
                    buildFollowUpQuery(section.getTitle()),
                    RagRetrievalContext.builder()
                            .sourceName(sourceName)
                            .sourceType(sourceType)
                            .contentPath(parentContentPath)
                            .build(),
                    contentGoldResolution
            ));

            String topicSwitchQuery = buildTopicSwitchQuery(sections, i);
            if (StringUtils.hasText(topicSwitchQuery)) {
                queryCases.add(createCase(
                        source,
                        QUERY_STYLE_TOPIC_SWITCH_GUARD,
                        kbId,
                        docId,
                        i,
                        topicSwitchQuery,
                        RagRetrievalContext.builder()
                                .sourceName(sourceName)
                                .sourceType(sourceType)
                                .contentPath(parentContentPath)
                                .build(),
                        resolveTitleAnchorGoldChunks(docId, sections.get(i + 1), sortedChunks)
                ));
            }
        }

        return queryCases;
    }

    private GoldResolution resolveTitleAnchorGoldChunks(
            String docId,
            MarkdownParserService.MarkdownSection section,
            List<ChunkBgeM3> persistedChunks
    ) {
        String normalizedTitle = normalizeTitle(section.getTitle());
        if (!StringUtils.hasText(normalizedTitle)) {
            return new GoldResolution(List.of(), GOLD_NOT_FOUND);
        }

        List<ChunkBgeM3> titleMatches = persistedChunks.stream()
                .filter(chunk -> docId.equals(chunk.getDocId()))
                .filter(chunk -> normalizeTitle(extractRetrievableTitle(chunk.getMetadata())).equals(normalizedTitle))
                .toList();
        if (!titleMatches.isEmpty()) {
            return new GoldResolution(
                    titleMatches.stream().map(ChunkBgeM3::getId).toList(),
                    GOLD_TITLE_ANCHOR
            );
        }

        return new GoldResolution(List.of(), GOLD_NOT_FOUND);
    }

    private QueryCase createCase(
            String source,
            String queryStyle,
            String kbId,
            String docId,
            int sectionIndex,
            String query,
            GoldResolution goldResolution
    ) {
        return createCase(source, queryStyle, kbId, docId, sectionIndex, query, null, goldResolution);
    }

    private QueryCase createCase(
            String source,
            String queryStyle,
            String kbId,
            String docId,
            int sectionIndex,
            String query,
            RagRetrievalContext context,
            GoldResolution goldResolution
    ) {
        String caseId = source + "/" + queryStyle + "/" + docId + "/" + sectionIndex;
        String excludedReason = excludedReason(queryStyle, query, goldResolution);
        boolean evaluable = excludedReason == null;
        return new QueryCase(
                caseId,
                queryStyle,
                kbId,
                query,
                context,
                goldResolution.chunkIds(),
                goldResolution.mode(),
                goldResolution.chunkIds().size(),
                excludedReason,
                evaluable
        );
    }

    private GoldResolution resolveGoldChunks(
            String docId,
            MarkdownParserService.MarkdownSection section,
            List<ChunkBgeM3> persistedChunks,
            int sectionIndex
    ) {
        List<ChunkBgeM3> exactMatches = persistedChunks.stream()
                .filter(chunk -> docId.equals(chunk.getDocId()))
                .filter(chunk -> normalize(chunk.getContent()).equals(normalize(section.getContent())))
                .toList();
        if (!exactMatches.isEmpty()) {
            return new GoldResolution(
                    exactMatches.stream().map(ChunkBgeM3::getId).toList(),
                    GOLD_EXACT_CONTENT
            );
        }

        List<ChunkBgeM3> metadataMatches = persistedChunks.stream()
                .filter(chunk -> docId.equals(chunk.getDocId()))
                .filter(chunk -> sectionIndexFromMetadata(chunk.getMetadata()) == sectionIndex)
                .toList();
        if (!metadataMatches.isEmpty()) {
            return new GoldResolution(
                    metadataMatches.stream().map(ChunkBgeM3::getId).toList(),
                    GOLD_METADATA_SECTION_INDEX
            );
        }

        if (sectionIndex >= 0 && sectionIndex < persistedChunks.size()) {
            ChunkBgeM3 candidate = persistedChunks.get(sectionIndex);
            if (normalize(candidate.getContent()).equals(normalize(section.getContent()))) {
                return new GoldResolution(List.of(candidate.getId()), GOLD_SECTION_ORDER_EXACT);
            }
        }

        List<ChunkBgeM3> overlapMatches = persistedChunks.stream()
                .filter(chunk -> docId.equals(chunk.getDocId()))
                .filter(chunk -> contentOverlaps(section.getContent(), chunk.getContent()))
                .toList();
        if (!overlapMatches.isEmpty()) {
            return new GoldResolution(
                    overlapMatches.stream().map(ChunkBgeM3::getId).toList(),
                    GOLD_CONTENT_OVERLAP
            );
        }

        return new GoldResolution(List.of(), GOLD_NOT_FOUND);
    }

    private String extractSourceName(GoldResolution goldResolution, List<ChunkBgeM3> persistedChunks) {
        return extractMetadataText(goldResolution, persistedChunks, "sourceName");
    }

    private String extractSourceType(GoldResolution goldResolution, List<ChunkBgeM3> persistedChunks) {
        return extractMetadataText(goldResolution, persistedChunks, "sourceType");
    }

    private String extractMetadataText(GoldResolution goldResolution, List<ChunkBgeM3> persistedChunks, String fieldName) {
        if (goldResolution.chunkIds().isEmpty()) {
            return null;
        }
        String chunkId = goldResolution.chunkIds().get(0);
        return persistedChunks.stream()
                .filter(chunk -> chunkId.equals(chunk.getId()))
                .findFirst()
                .map(chunk -> extractMetadataText(chunk.getMetadata(), fieldName))
                .orElse(null);
    }

    private String parentContentPath(String contentPath) {
        if (!StringUtils.hasText(contentPath)) {
            return null;
        }
        int separatorIndex = contentPath.lastIndexOf(" > ");
        if (separatorIndex <= 0) {
            return contentPath;
        }
        return contentPath.substring(0, separatorIndex);
    }

    private String buildPathAwareTitleQuery(String parentContentPath, String title) {
        if (!StringUtils.hasText(parentContentPath)) {
            return title;
        }
        return parentContentPath + " > " + title;
    }

    private String buildFollowUpQuery(String title) {
        if (!StringUtils.hasText(title)) {
            return null;
        }
        String normalizedTitle = normalize(title);
        if (!StringUtils.hasText(normalizedTitle)) {
            return null;
        }
        if (normalizedTitle.contains("回答")) {
            return "这部分该怎么展开";
        }
        if (normalizedTitle.contains("流程")) {
            return "这一步具体怎么走";
        }
        return "这部分该怎么处理";
    }

    private String buildUserLikeQuestion(MarkdownParserService.MarkdownSection section) {
        if (section == null) {
            return null;
        }
        String title = normalize(section.getTitle());
        String focus = buildQuestionFocus(section);
        if (!StringUtils.hasText(focus)) {
            return buildRewriteQuery(section.getContent());
        }
        if (isGenericQaLeafTitle(title)) {
            return focus + " 如何使用";
        }
        if (!StringUtils.hasText(title)) {
            return focus + " 如何使用";
        }
        if (title.contains("为什么")) {
            return "为什么" + focus + "的性能好";
        }
        if (title.contains("流程")) {
            return focus + " 如何使用";
        }
        if (title.contains("原理")) {
            return focus + "的原理是什么";
        }
        if (title.contains("区别") || title.contains("对比")) {
            String contrastTarget = buildContrastTarget(section);
            return contrastTarget == null
                    ? focus + "有什么区别"
                    : focus + "和" + contrastTarget + "有什么区别";
        }
        if (title.contains("优缺点")) {
            return "为什么" + focus + "的性能好";
        }
        if (title.contains("怎么") || title.contains("如何")) {
            return focus + " 如何使用";
        }
        if (title.contains("总结")) {
            return focus + "的原理是什么";
        }
        if (title.contains("方案")) {
            return "为什么" + focus + "的性能好";
        }
        return focus + " 是什么";
    }

    private String buildContrastTarget(MarkdownParserService.MarkdownSection section) {
        String parentContentPath = normalize(section.getParentContentPath());
        if (!StringUtils.hasText(parentContentPath)) {
            return null;
        }
        int separatorIndex = parentContentPath.lastIndexOf(" > ");
        if (separatorIndex < 0) {
            return null;
        }
        String siblingOrParent = parentContentPath.substring(separatorIndex + 3).trim();
        return StringUtils.hasText(siblingOrParent) && !siblingOrParent.equals(normalize(section.getTitle()))
                ? siblingOrParent
                : null;
    }

    private String buildQuestionFocus(MarkdownParserService.MarkdownSection section) {
        String title = normalize(section.getTitle());
        String parentContentPath = normalize(section.getParentContentPath());
        if (isGenericQaLeafTitle(title) && StringUtils.hasText(parentContentPath)) {
            return lastPathSegment(parentContentPath);
        }
        if (StringUtils.hasText(title)) {
            return title;
        }
        if (StringUtils.hasText(parentContentPath)) {
            return lastPathSegment(parentContentPath);
        }
        return null;
    }

    private boolean isGenericQaLeafTitle(String title) {
        return "回答".equals(title)
                || "原理".equals(title)
                || "总结".equals(title)
                || "方案".equals(title);
    }

    private String lastPathSegment(String contentPath) {
        if (!StringUtils.hasText(contentPath)) {
            return null;
        }
        int separatorIndex = contentPath.lastIndexOf(" > ");
        if (separatorIndex < 0) {
            return contentPath;
        }
        return contentPath.substring(separatorIndex + 3).trim();
    }

    private String buildTopicSwitchQuery(List<MarkdownParserService.MarkdownSection> sections, int currentIndex) {
        if (currentIndex + 1 >= sections.size()) {
            return null;
        }
        MarkdownParserService.MarkdownSection nextSection = sections.get(currentIndex + 1);
        return nextSection.getContentPath();
    }

    private Document ingestFixtureMarkdown(String kbId, Resource resource) throws Exception {
        byte[] bytes;
        try (InputStream inputStream = resource.getInputStream()) {
            bytes = inputStream.readAllBytes();
        }

        String originalFilename = resource.getFilename();
        String documentId = insertDocumentRecord(kbId, originalFilename, bytes.length);
        MultipartFile multipartFile = new InMemoryMultipartFile(originalFilename, "text/markdown", bytes);
        String filePath = documentStorageService.saveFile(kbId, documentId, multipartFile);
        updateDocumentMetadata(documentId, kbId, originalFilename, bytes.length, filePath);
        createChunksFromMarkdown(kbId, documentId, filePath, originalFilename, "md");
        return documentMapper.selectById(documentId);
    }

    private void cleanupKnowledgeBaseDocuments(String kbId) throws Exception {
        for (Document document : documentMapper.selectByKbId(kbId)) {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && StringUtils.hasText(documentDTO.getMetadata().getFilePath())) {
                documentStorageService.deleteFile(documentDTO.getMetadata().getFilePath());
            }
            documentMapper.deleteById(document.getId());
        }
    }

    private String insertDocumentRecord(String kbId, String filename, long size) throws JsonProcessingException {
        return insertDocumentRecord(kbId, filename, size, "md");
    }

    private String insertDocumentRecord(String kbId, String filename, long size, String filetype) throws JsonProcessingException {
        DocumentDTO documentDTO = DocumentDTO.builder()
                .kbId(kbId)
                .filename(filename)
                .filetype(filetype)
                .size(size)
                .build();

        Document document = documentConverter.toEntity(documentDTO);
        LocalDateTime now = LocalDateTime.now();
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentMapper.insert(document);
        return document.getId();
    }

    private void updateDocumentMetadata(String documentId, String kbId, String filename, long size, String filePath) throws JsonProcessingException {
        updateDocumentMetadata(documentId, kbId, filename, size, filePath, "md");
    }

    private void updateDocumentMetadata(String documentId, String kbId, String filename, long size, String filePath, String filetype) throws JsonProcessingException {
        DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
        metadata.setFilePath(filePath);

        DocumentDTO documentDTO = DocumentDTO.builder()
                .id(documentId)
                .kbId(kbId)
                .filename(filename)
                .filetype(filetype)
                .size(size)
                .metadata(metadata)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Document document = documentConverter.toEntity(documentDTO);
        document.setId(documentId);
        documentMapper.updateById(document);
    }

    private void createChunksFromMarkdown(
            String kbId,
            String documentId,
            String filePath,
            String sourceName,
            String sourceType
    ) throws Exception {
        Path path = documentStorageService.getFilePath(filePath);
        try (InputStream inputStream = Files.newInputStream(path)) {
            List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);
            LocalDateTime now = LocalDateTime.now();

            for (int i = 0; i < sections.size(); i++) {
                MarkdownParserService.MarkdownSection section = sections.get(i);
                String title = section.getTitle();
                if (!StringUtils.hasText(title)) {
                    continue;
                }

                float[] embedding = ragService.embed(RagChunkSupport.buildChunkEmbeddingText(section));
                ChunkBgeM3 chunk = ChunkBgeM3.builder()
                        .kbId(kbId)
                        .docId(documentId)
                        .content(section.getContent() != null ? section.getContent() : "")
                        .metadata(RagChunkSupport.buildChunkMetadataJson(objectMapper, section, sourceType, sourceName, i))
                        .embedding(embedding)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                chunkBgeM3Mapper.insert(chunk);
            }
        }
    }

    private KnowledgeBase createKnowledgeBase(String name, String description) throws JsonProcessingException {
        KnowledgeBaseDTO dto = KnowledgeBaseDTO.builder()
                .name(name)
                .description(description)
                .build();
        KnowledgeBase knowledgeBase = knowledgeBaseConverter.toEntity(dto);
        LocalDateTime now = LocalDateTime.now();
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase;
    }

    private List<MarkdownParserService.MarkdownSection> parseMarkdownByDocument(Document document) throws Exception {
        DocumentDTO documentDTO = documentConverter.toDTO(document);
        try (InputStream inputStream = Files.newInputStream(documentStorageService.getFilePath(documentDTO.getMetadata().getFilePath()))) {
            return markdownParserService.parseMarkdown(inputStream);
        }
    }

    private void cleanupFixtureData() throws Exception {
        for (KnowledgeBase knowledgeBase : knowledgeBaseMapper.selectAll()) {
            if (!FIXTURE_KB_NAME.equals(knowledgeBase.getName())) {
                continue;
            }
            for (Document document : documentMapper.selectByKbId(knowledgeBase.getId())) {
                DocumentDTO documentDTO = documentConverter.toDTO(document);
                if (documentDTO.getMetadata() != null && StringUtils.hasText(documentDTO.getMetadata().getFilePath())) {
                    documentStorageService.deleteFile(documentDTO.getMetadata().getFilePath());
                }
                documentMapper.deleteById(document.getId());
            }
            knowledgeBaseMapper.deleteById(knowledgeBase.getId());
        }
    }

    private boolean shouldRunFixture() {
        return MODE_FIXTURE.equalsIgnoreCase(evalMode) || MODE_BOTH.equalsIgnoreCase(evalMode);
    }

    private boolean shouldRunReal() {
        return MODE_REAL.equalsIgnoreCase(evalMode) || MODE_BOTH.equalsIgnoreCase(evalMode);
    }

    private boolean isMarkdown(String fileType) {
        return "md".equalsIgnoreCase(fileType) || "markdown".equalsIgnoreCase(fileType);
    }

    private String buildRewriteQuery(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String normalized = normalize(content)
                .replace("-", " ")
                .replace("*", " ")
                .replace("|", " ");
        String[] sentences = normalized.split("[。！？；\\n]");
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.length() >= 12) {
                return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
            }
        }
        return normalized.length() > 12 ? normalized.substring(0, Math.min(normalized.length(), 40)) : null;
    }

    private String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replace("\r", "")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean contentOverlaps(String sectionContent, String chunkContent) {
        String normalizedSection = normalize(sectionContent);
        String normalizedChunk = normalize(chunkContent);
        if (!StringUtils.hasText(normalizedSection) || !StringUtils.hasText(normalizedChunk)) {
            return false;
        }
        return normalizedSection.contains(normalizedChunk) || normalizedChunk.contains(normalizedSection);
    }

    private int sectionIndexFromMetadata(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return -1;
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode sectionIndexNode = root.get("sectionIndex");
            return sectionIndexNode != null && sectionIndexNode.canConvertToInt() ? sectionIndexNode.asInt() : -1;
        } catch (JsonProcessingException e) {
            return -1;
        }
    }

    private String extractRetrievableTitle(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode titleNode = root.get("retrievableTitle");
            if (titleNode == null || !titleNode.isTextual()) {
                titleNode = root.get("title");
            }
            return titleNode != null && titleNode.isTextual() ? titleNode.asText() : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private String extractMetadataText(String metadata, String fieldName) {
        if (!StringUtils.hasText(metadata)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode node = root.get(fieldName);
            return node != null && node.isTextual() ? node.asText() : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private String normalizeTitle(String title) {
        return RetrievableTitleLexicalizer.normalize(title);
    }

    private String excludedReason(String queryStyle, String query, GoldResolution goldResolution) {
        if (!StringUtils.hasText(query)) {
            return QUERY_STYLE_REWRITE.equals(queryStyle) ? EXCLUDED_EMPTY_REWRITE_QUERY : EXCLUDED_EMPTY_QUERY;
        }
        if (goldResolution.chunkIds().isEmpty()) {
            return EXCLUDED_MISSING_GOLD_CHUNK;
        }
        return null;
    }

    private double hitRate(List<EvaluatedCase> cases, int k) {
        if (cases.isEmpty()) {
            return 0D;
        }
        return ratio(hitCount(cases, k), cases.size());
    }

    private int hitCount(List<EvaluatedCase> cases, int k) {
        return (int) cases.stream().filter(item -> item.hitAt(k)).count();
    }

    private double mrrAt(List<EvaluatedCase> cases, int k) {
        if (cases.isEmpty()) {
            return 0D;
        }
        return cases.stream()
                .mapToDouble(item -> reciprocalRank(item, k))
                .average()
                .orElse(0D);
    }

    private double reciprocalRank(EvaluatedCase evaluatedCase, int k) {
        int limit = Math.min(k, evaluatedCase.topChunkIds().size());
        for (int i = 0; i < limit; i++) {
            if (evaluatedCase.goldChunkIds().contains(evaluatedCase.topChunkIds().get(i))) {
                return 1D / (i + 1);
            }
        }
        return 0D;
    }

    private HitDistribution hitDistribution(List<EvaluatedCase> cases) {
        int hitAt1 = 0;
        int hitAt3Not1 = 0;
        int hitAt5Not3 = 0;
        int missAt5 = 0;
        for (EvaluatedCase item : cases) {
            if (item.hitAt(1)) {
                hitAt1++;
            } else if (item.hitAt(3)) {
                hitAt3Not1++;
            } else if (item.hitAt(5)) {
                hitAt5Not3++;
            } else {
                missAt5++;
            }
        }
        return new HitDistribution(hitAt1, hitAt3Not1, hitAt5Not3, missAt5);
    }

    private HitDistribution mergeHitDistributions(List<EvaluationSummary> summaries) {
        int hitAt1 = 0;
        int hitAt3Not1 = 0;
        int hitAt5Not3 = 0;
        int missAt5 = 0;
        for (EvaluationSummary summary : summaries) {
            HitDistribution distribution = summary.hitDistribution();
            hitAt1 += distribution.hitAt1();
            hitAt3Not1 += distribution.hitAt3Not1();
            hitAt5Not3 += distribution.hitAt5Not3();
            missAt5 += distribution.missAt5();
        }
        return new HitDistribution(hitAt1, hitAt3Not1, hitAt5Not3, missAt5);
    }

    private Map<String, Integer> mergeExcludedReasons(List<EvaluationSummary> summaries) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (EvaluationSummary summary : summaries) {
            for (Map.Entry<String, Integer> entry : summary.excludedReasons().entrySet()) {
                addCount(result, entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private double weightedAverage(List<EvaluationSummary> summaries, int k) {
        int evaluated = summaries.stream().mapToInt(EvaluationSummary::evaluated).sum();
        if (evaluated == 0) {
            return 0D;
        }
        return summaries.stream()
                .mapToDouble(summary -> (k == 3 ? summary.mrrAt3() : summary.mrrAt10()) * summary.evaluated())
                .sum() / evaluated;
    }

    private void addCount(Map<String, Integer> counts, String key) {
        addCount(counts, key, 1);
    }

    private void addCount(Map<String, Integer> counts, String key, int value) {
        counts.merge(key, value, Integer::sum);
    }

    private double ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return 0D;
        }
        return (double) numerator / denominator;
    }

    private Map<String, Set<String>> loadPreviousMissCaseIds() throws IOException {
        if (!StringUtils.hasText(compareWithReportPath)) {
            return Map.of();
        }
        Path path = Path.of(compareWithReportPath);
        if (!Files.exists(path)) {
            return Map.of();
        }

        JsonNode root = objectMapper.readTree(Files.readString(path));
        Map<String, Set<String>> missCaseIdsByGroup = new LinkedHashMap<>();
        if (root.isArray()) {
            for (JsonNode summary : root) {
                collectPreviousMissCaseIds(summary, missCaseIdsByGroup);
            }
        } else {
            collectPreviousMissCaseIds(root, missCaseIdsByGroup);
        }
        return missCaseIdsByGroup;
    }

    private void collectPreviousMissCaseIds(JsonNode summary, Map<String, Set<String>> missCaseIdsByGroup) {
        JsonNode groupNode = summary.get("group");
        if (groupNode == null || !groupNode.isTextual()) {
            return;
        }

        String group = groupNode.asText();
        Set<String> missCaseIds = new LinkedHashSet<>();
        JsonNode missCaseIdsNode = summary.get("missCaseIds");
        if (missCaseIdsNode != null && missCaseIdsNode.isArray()) {
            for (JsonNode item : missCaseIdsNode) {
                if (item.isTextual()) {
                    missCaseIds.add(item.asText());
                }
            }
        } else {
            JsonNode missCasesNode = summary.get("missCases");
            if (missCasesNode != null && missCasesNode.isArray()) {
                for (JsonNode item : missCasesNode) {
                    if (item.isTextual()) {
                        missCaseIds.add(parseCaseId(item.asText()));
                    }
                }
            }
        }
        missCaseIdsByGroup.put(group, missCaseIds);

        JsonNode breakdownNode = summary.get("breakdown");
        if (breakdownNode != null && breakdownNode.isArray()) {
            for (JsonNode item : breakdownNode) {
                collectPreviousMissCaseIds(item, missCaseIdsByGroup);
            }
        }
    }

    private String parseCaseId(String missCase) {
        int separatorIndex = missCase.indexOf(" | ");
        return separatorIndex >= 0 ? missCase.substring(0, separatorIndex) : missCase;
    }

    private List<String> newMissCases(
            String group,
            List<String> currentMissCaseIds,
            Map<String, Set<String>> previousMissCaseIds
    ) {
        Set<String> previous = previousMissCaseIds.get(group);
        if (previous == null) {
            return List.of();
        }
        return currentMissCaseIds.stream()
                .filter(caseId -> !previous.contains(caseId))
                .limit(DIFF_CASE_LIMIT)
                .toList();
    }

    private List<String> fixedMissCases(
            String group,
            List<String> currentMissCaseIds,
            Map<String, Set<String>> previousMissCaseIds
    ) {
        Set<String> previous = previousMissCaseIds.get(group);
        if (previous == null) {
            return List.of();
        }
        Set<String> current = new LinkedHashSet<>(currentMissCaseIds);
        return previous.stream()
                .filter(caseId -> !current.contains(caseId))
                .limit(DIFF_CASE_LIMIT)
                .toList();
    }

    private double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0D;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
    }

    private void assertThreshold(double actual, double expected, String label) {
        if (actual < expected) {
            throw new AssertionError(label + " 低于阈值，actual=" + actual + ", expected=" + expected);
        }
    }

    private String formatDelta(double value) {
        return value >= 0 ? String.format("+%.4f", value) : String.format("%.4f", value);
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
            DocumentConverter.class,
            KnowledgeBaseConverter.class,
            DocumentStorageServiceImpl.class,
            MarkdownParserServiceImpl.class,
            QueryRewriteServiceImpl.class,
            RagServiceImpl.class
    })
    static class RagEvalTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private record QueryCase(
            String caseId,
            String queryStyle,
            String kbId,
            String query,
            RagRetrievalContext context,
            List<String> goldChunkIds,
            String goldResolutionMode,
            int goldCandidateCount,
            String excludedReason,
            boolean evaluable
    ) {
    }

    private record EvaluatedCase(
            String caseId,
            List<String> goldChunkIds,
            String goldResolutionMode,
            int goldCandidateCount,
            List<String> topChunkIds
    ) {
        boolean hitAt(int k) {
            return topChunkIds.stream().limit(k).anyMatch(goldChunkIds::contains);
        }
    }

    private record EvaluationSummary(
            String group,
            int total,
            int evaluated,
            int excluded,
            double coverage,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double recallAt10,
            double weightedRecallAt1,
            double weightedRecallAt3,
            double weightedRecallAt5,
            double weightedRecallAt10,
            int hitAt1Count,
            int hitAt3Count,
            int hitAt5Count,
            int hitAt10Count,
            double mrrAt3,
            double mrrAt10,
            HitDistribution hitDistribution,
            Map<String, Integer> excludedReasons,
            Map<String, Integer> skippedDocumentReasons,
            List<String> missCases,
            List<String> missCaseIds,
            List<String> newMissCases,
            List<String> fixedMissCases,
            List<DimensionSummary> dimensions,
            List<EvaluationSummary> breakdown,
            List<VariantComparison> comparisons
    ) {
    }

    private record DimensionSummary(
            String name,
            String description,
            List<String> groups,
            int total,
            int evaluated,
            int excluded,
            double coverage,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double recallAt10,
            double mrrAt3,
            double mrrAt10
    ) {
    }

    private record HitDistribution(
            int hitAt1,
            int hitAt3Not1,
            int hitAt5Not3,
            int missAt5
    ) {
    }

    private record VariantComparison(
            String variant,
            String description,
            List<VariantComparisonItem> items
    ) {
    }

    private record VariantComparisonItem(
            String group,
            double baselineRecallAt1,
            double baselineRecallAt5,
            double baselineMrrAt3,
            double comparedRecallAt1,
            double comparedRecallAt5,
            double comparedMrrAt3,
            double deltaRecallAt1,
            double deltaRecallAt5,
            double deltaMrrAt3
    ) {
    }

    private record GoldResolution(
            List<String> chunkIds,
            String mode
    ) {
    }

    private static class InMemoryMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        private InMemoryMultipartFile(String originalFilename, String contentType, byte[] content) {
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }

        @Override
        public void transferTo(Path dest) throws IOException {
            Files.write(dest, content);
        }
    }

    private record RealDocumentSnapshot(
            String filename,
            String filetype,
            byte[] bytes
    ) {
    }
}
