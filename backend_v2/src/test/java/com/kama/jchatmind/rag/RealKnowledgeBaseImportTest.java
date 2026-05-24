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
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.DocumentStorageServiceImpl;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.service.impl.QueryRewriteServiceImpl;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import com.kama.jchatmind.service.impl.RetrievableTitleLexicalizer;
import com.kama.jchatmind.util.RagChunkSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = RealKnowledgeBaseImportTest.ImportTestConfig.class)
@ActiveProfiles("rag-eval")
class RealKnowledgeBaseImportTest {

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
    private RagService ragService;

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${rag.eval.import.kb-name:RAG Eval Imported KB}")
    private String kbName;

    @Value("${rag.eval.import.kb-id:}")
    private String kbId;

    @Value("${rag.eval.import.file-paths:}")
    private String importFilePaths;

    @Value("${rag.eval.import.clean-kb:true}")
    private boolean cleanKnowledgeBase;

    @Value("${document.storage.base-path}")
    private String documentStorageBasePath;

    @Test
    void importMarkdownFilesToKnowledgeBase() throws Exception {
        List<Path> markdownPaths = resolveMarkdownPaths(importFilePaths);
        Assumptions.assumeTrue(!markdownPaths.isEmpty(), "缺少 rag.eval.import.file-paths，跳过知识库导入");

        Files.createDirectories(Path.of(documentStorageBasePath));
        KnowledgeBase knowledgeBase = resolveKnowledgeBase();
        if (cleanKnowledgeBase) {
            cleanupKnowledgeBaseDocuments(knowledgeBase.getId());
        }

        List<ImportedDocument> importedDocuments = new ArrayList<>();
        for (Path markdownPath : markdownPaths) {
            ImportedDocument importedDocument = importMarkdownFile(knowledgeBase.getId(), markdownPath);
            importedDocuments.add(importedDocument);
        }

        Path reportOutputPath = Path.of("target", "rag-eval", "import-report.json");
        Files.createDirectories(reportOutputPath.getParent());
        ImportReport report = new ImportReport(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                importedDocuments
        );
        Files.writeString(
                reportOutputPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        );

        System.out.println("IMPORTED_KB_ID=" + knowledgeBase.getId());
        System.out.println("IMPORTED_DOC_COUNT=" + importedDocuments.size());
        System.out.println("IMPORT_REPORT=" + reportOutputPath.toAbsolutePath());
    }

    private List<Path> resolveMarkdownPaths(String configuredPaths) {
        if (!StringUtils.hasText(configuredPaths)) {
            return List.of();
        }
        return Arrays.stream(configuredPaths.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(Path::of)
                .filter(Files::exists)
                .toList();
    }

    private KnowledgeBase resolveKnowledgeBase() throws JsonProcessingException {
        if (StringUtils.hasText(kbId)) {
            KnowledgeBase existing = knowledgeBaseMapper.selectById(kbId.trim());
            if (existing != null) {
                return existing;
            }
            throw new IllegalArgumentException("指定的知识库不存在: " + kbId);
        }

        for (KnowledgeBase knowledgeBase : knowledgeBaseMapper.selectAll()) {
            if (kbName.equals(knowledgeBase.getName())) {
                return knowledgeBase;
            }
        }

        KnowledgeBaseDTO dto = KnowledgeBaseDTO.builder()
                .name(kbName)
                .description("用于 RAG 评测导入的真实知识库")
                .build();
        KnowledgeBase knowledgeBase = knowledgeBaseConverter.toEntity(dto);
        LocalDateTime now = LocalDateTime.now();
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase;
    }

    private void cleanupKnowledgeBaseDocuments(String targetKbId) throws Exception {
        for (Document document : documentMapper.selectByKbId(targetKbId)) {
            jdbcTemplate.update("DELETE FROM chunk_bge_m3 WHERE doc_id = CAST(? AS uuid)", document.getId());

            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && StringUtils.hasText(documentDTO.getMetadata().getFilePath())) {
                documentStorageService.deleteFile(documentDTO.getMetadata().getFilePath());
            }
            documentMapper.deleteById(document.getId());
        }
    }

    private ImportedDocument importMarkdownFile(String targetKbId, Path markdownPath) throws Exception {
        byte[] bytes = Files.readAllBytes(markdownPath);
        String filename = markdownPath.getFileName().toString();
        String documentId = insertDocumentRecord(targetKbId, filename, bytes.length);
        MultipartFile multipartFile = new InMemoryMultipartFile(filename, "text/markdown", bytes);
        String filePath = documentStorageService.saveFile(targetKbId, documentId, multipartFile);
        updateDocumentMetadata(documentId, targetKbId, filename, bytes.length, filePath);
        int chunkCount = createChunksFromMarkdown(targetKbId, documentId, filePath, filename, "md");
        String lastModifiedAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(Files.getLastModifiedTime(markdownPath).toMillis()),
                ZoneId.systemDefault()
        ).toString();
        return new ImportedDocument(
                filename,
                markdownPath.toAbsolutePath().toString(),
                bytes.length,
                lastModifiedAt,
                documentId,
                chunkCount
        );
    }

    private String insertDocumentRecord(String targetKbId, String filename, long size) throws JsonProcessingException {
        DocumentDTO documentDTO = DocumentDTO.builder()
                .kbId(targetKbId)
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

    private void updateDocumentMetadata(
            String documentId,
            String targetKbId,
            String filename,
            long size,
            String filePath
    ) throws JsonProcessingException {
        DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
        metadata.setFilePath(filePath);
        DocumentDTO documentDTO = DocumentDTO.builder()
                .id(documentId)
                .kbId(targetKbId)
                .filename(filename)
                .filetype("md")
                .size(size)
                .metadata(metadata)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Document document = documentConverter.toEntity(documentDTO);
        document.setId(documentId);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
    }

    private int createChunksFromMarkdown(
            String targetKbId,
            String documentId,
            String filePath,
            String sourceName,
            String sourceType
    ) throws Exception {
        int chunkCount = 0;
        try (InputStream inputStream = Files.newInputStream(documentStorageService.getFilePath(filePath))) {
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
                        .kbId(targetKbId)
                        .docId(documentId)
                        .content(section.getContent() != null ? section.getContent() : "")
                        .metadata(RagChunkSupport.buildChunkMetadataJson(objectMapper, section, sourceType, sourceName, i))
                        .embedding(embedding)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                chunkBgeM3Mapper.insert(chunk);
                chunkCount++;
            }
        }
        return chunkCount;
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
    static class ImportTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private record ImportReport(
            String kbId,
            String kbName,
            List<ImportedDocument> documents
    ) {
    }

    private record ImportedDocument(
            String filename,
            String absolutePath,
            long size,
            String lastModifiedAt,
            String documentId,
            int chunkCount
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
