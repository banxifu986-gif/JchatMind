package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.DocumentStorageServiceImpl;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest(classes = RagRecallEvaluationTest.RagEvalTestConfig.class)
@ActiveProfiles("rag-eval")
class RagRecallEvaluationTest {

    private static final String FIXTURE_KB_NAME = "RAG Recall Fixture KB";
    private static final String MODE_FIXTURE = "fixture";
    private static final String MODE_REAL = "real";
    private static final String MODE_BOTH = "both";
    private static final String QUERY_STYLE_TITLE = "title_exact";
    private static final String QUERY_STYLE_REWRITE = "content_rewrite";

    @Autowired
    private RagService ragService;

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
    }

    @Test
    void evaluateRecall() throws Exception {
        List<EvaluationSummary> summaries = new ArrayList<>();

        if (shouldRunFixture()) {
            summaries.add(runFixtureEvaluation());
        }

        if (shouldRunReal()) {
            Assumptions.assumeTrue(StringUtils.hasText(realKbId), "缺少 rag.eval.real-kb-id，跳过真实知识库评测");
            summaries.add(runRealEvaluation(realKbId));
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

    private EvaluationSummary runFixtureEvaluation() throws Exception {
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
        return evaluateCases("fixture", queryCases);
    }

    private EvaluationSummary runRealEvaluation(String kbId) throws Exception {
        List<QueryCase> queryCases = new ArrayList<>();
        List<Document> documents = documentMapper.selectByKbId(kbId);

        for (Document document : documents) {
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

            List<MarkdownParserService.MarkdownSection> sections = parseMarkdownByDocument(document);
            List<ChunkBgeM3> persistedChunks = chunkBgeM3Mapper.selectByDocId(document.getId());
            queryCases.addAll(buildQueryCases(kbId, document.getId(), sections, persistedChunks, "real"));
        }

        return evaluateCases("real", queryCases);
    }

    private EvaluationSummary evaluateCases(String source, List<QueryCase> cases) {
        Map<String, List<QueryCase>> caseGroups = cases.stream()
                .collect(Collectors.groupingBy(QueryCase::queryStyle, LinkedHashMap::new, Collectors.toList()));

        List<EvaluationSummary> breakdown = new ArrayList<>();
        for (String queryStyle : List.of(QUERY_STYLE_TITLE, QUERY_STYLE_REWRITE)) {
            breakdown.add(evaluateStyleGroup(source + "/" + queryStyle, caseGroups.getOrDefault(queryStyle, List.of())));
        }

        int total = breakdown.stream().mapToInt(EvaluationSummary::total).sum();
        int evaluated = breakdown.stream().mapToInt(EvaluationSummary::evaluated).sum();
        int excluded = breakdown.stream().mapToInt(EvaluationSummary::excluded).sum();
        double recallAt1 = average(breakdown.stream().map(EvaluationSummary::recallAt1).toList());
        double recallAt3 = average(breakdown.stream().map(EvaluationSummary::recallAt3).toList());
        double recallAt5 = average(breakdown.stream().map(EvaluationSummary::recallAt5).toList());
        List<String> missCases = breakdown.stream()
                .flatMap(summary -> summary.missCases().stream())
                .limit(10)
                .toList();

        return new EvaluationSummary(source, total, evaluated, excluded, recallAt1, recallAt3, recallAt5, missCases, breakdown);
    }

    private EvaluationSummary evaluateStyleGroup(String group, List<QueryCase> cases) {
        List<EvaluatedCase> evaluatedCases = new ArrayList<>();
        int excluded = 0;

        for (QueryCase queryCase : cases) {
            if (!queryCase.evaluable()) {
                excluded++;
                continue;
            }

            List<RagRetrievalResult> results = ragService.retrieve(queryCase.kbId(), queryCase.query(), 5);
            List<String> topChunkIds = results.stream()
                    .map(RagRetrievalResult::getChunkId)
                    .toList();
            evaluatedCases.add(new EvaluatedCase(
                    queryCase.caseId(),
                    queryCase.goldChunkId(),
                    topChunkIds
            ));
        }

        int total = cases.size();
        int evaluated = evaluatedCases.size();
        double recallAt1 = hitRate(evaluatedCases, 1);
        double recallAt3 = hitRate(evaluatedCases, 3);
        double recallAt5 = hitRate(evaluatedCases, 5);
        List<String> missCases = evaluatedCases.stream()
                .filter(item -> !item.hitAt(5))
                .map(item -> item.caseId() + " | gold=" + item.goldChunkId() + " | top=" + item.topChunkIds())
                .limit(10)
                .toList();

        return new EvaluationSummary(group, total, evaluated, excluded, recallAt1, recallAt3, recallAt5, missCases, List.of());
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
            String goldChunkId = resolveGoldChunkId(docId, section, sortedChunks, i);

            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_TITLE,
                    kbId,
                    docId,
                    i,
                    section.getTitle(),
                    goldChunkId
            ));

            String rewriteQuery = buildRewriteQuery(section.getContent());
            queryCases.add(createCase(
                    source,
                    QUERY_STYLE_REWRITE,
                    kbId,
                    docId,
                    i,
                    rewriteQuery,
                    goldChunkId
            ));
        }

        return queryCases;
    }

    private QueryCase createCase(
            String source,
            String queryStyle,
            String kbId,
            String docId,
            int sectionIndex,
            String query,
            String goldChunkId
    ) {
        String caseId = source + "/" + queryStyle + "/" + docId + "/" + sectionIndex;
        boolean evaluable = StringUtils.hasText(query) && StringUtils.hasText(goldChunkId);
        return new QueryCase(caseId, queryStyle, kbId, query, goldChunkId, evaluable);
    }

    private String resolveGoldChunkId(
            String docId,
            MarkdownParserService.MarkdownSection section,
            List<ChunkBgeM3> persistedChunks,
            int sectionIndex
    ) {
        List<ChunkBgeM3> exactMatches = persistedChunks.stream()
                .filter(chunk -> docId.equals(chunk.getDocId()))
                .filter(chunk -> normalize(chunk.getContent()).equals(normalize(section.getContent())))
                .toList();
        if (exactMatches.size() == 1) {
            return exactMatches.get(0).getId();
        }

        if (sectionIndex >= 0 && sectionIndex < persistedChunks.size()) {
            ChunkBgeM3 candidate = persistedChunks.get(sectionIndex);
            if (normalize(candidate.getContent()).equals(normalize(section.getContent()))) {
                return candidate.getId();
            }
        }
        return null;
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
        createChunksFromMarkdown(kbId, documentId, filePath);
        return documentMapper.selectById(documentId);
    }

    private String insertDocumentRecord(String kbId, String filename, long size) throws JsonProcessingException {
        DocumentDTO documentDTO = DocumentDTO.builder()
                .kbId(kbId)
                .filename(filename)
                .filetype("md")
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
        DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
        metadata.setFilePath(filePath);

        DocumentDTO documentDTO = DocumentDTO.builder()
                .id(documentId)
                .kbId(kbId)
                .filename(filename)
                .filetype("md")
                .size(size)
                .metadata(metadata)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Document document = documentConverter.toEntity(documentDTO);
        document.setId(documentId);
        documentMapper.updateById(document);
    }

    private void createChunksFromMarkdown(String kbId, String documentId, String filePath) throws Exception {
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

                float[] embedding = ragService.embed(buildChunkEmbeddingText(title, section.getContent()));
                ChunkBgeM3 chunk = ChunkBgeM3.builder()
                        .kbId(kbId)
                        .docId(documentId)
                        .content(section.getContent() != null ? section.getContent() : "")
                        .metadata(buildChunkMetadata(title, i))
                        .embedding(embedding)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                chunkBgeM3Mapper.insert(chunk);
            }
        }
    }

    private String buildChunkMetadata(String title, int sectionIndex) throws JsonProcessingException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("sectionIndex", sectionIndex);
        return objectMapper.writeValueAsString(metadata);
    }

    private String buildChunkEmbeddingText(String title, String content) {
        if (!StringUtils.hasText(content)) {
            return title;
        }
        return title + "\n" + content.trim();
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

    private double hitRate(List<EvaluatedCase> cases, int k) {
        if (cases.isEmpty()) {
            return 0D;
        }
        long hit = cases.stream().filter(item -> item.hitAt(k)).count();
        return (double) hit / cases.size();
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
            String goldChunkId,
            boolean evaluable
    ) {
    }

    private record EvaluatedCase(
            String caseId,
            String goldChunkId,
            List<String> topChunkIds
    ) {
        boolean hitAt(int k) {
            return topChunkIds.stream().limit(k).anyMatch(goldChunkId::equals);
        }
    }

    private record EvaluationSummary(
            String group,
            int total,
            int evaluated,
            int excluded,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            List<String> missCases,
            List<EvaluationSummary> breakdown
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
}
