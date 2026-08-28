package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentAssetMapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.DocumentAsset;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultIngestionTaskProcessorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldReplaceExistingChunksAndIndexStoredPlainTextDocument() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        Path storedFile = temporaryDirectory.resolve("notes.txt");
        Files.writeString(storedFile, "plain text");
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("notes.txt")
                .filetype("txt")
                .metadata("{\"filePath\":\"kb-1/doc-1/notes.txt\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/notes.txt")).thenReturn(storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1"))
                .thenReturn(List.of(ChunkBgeM3.builder().id("chunk-old").docId("doc-1").build()));
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(section()));
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenReturn(1);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper
        );

        process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build());

        verify(chunkBgeM3Mapper).deleteById("chunk-old");
        ArgumentCaptor<ChunkBgeM3> chunkCaptor = ArgumentCaptor.forClass(ChunkBgeM3.class);
        verify(chunkBgeM3Mapper).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue())
                .extracting(ChunkBgeM3::getKbId, ChunkBgeM3::getDocId, ChunkBgeM3::getContent)
                .containsExactly("kb-1", "doc-1", "正文");
    }

    @Test
    void shouldPersistVchordBm25ProjectionWithEachNewChunk() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        VchordBm25ProjectionService projectionService = mock(VchordBm25ProjectionService.class);
        Path storedFile = temporaryDirectory.resolve("notes.md");
        Files.writeString(storedFile, "# 标题\n正文");
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("notes.md")
                .filetype("md")
                .metadata("{\"filePath\":\"kb-1/doc-1/notes.md\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/notes.md")).thenReturn(storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(section()));
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        when(projectionService.project("标题 标题 标题 notes md", "正文")).thenReturn(
                new VchordBm25ProjectionService.Projection("{11:2}", "{42:1}", 1)
        );
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenReturn(1);
        Object processor = processorWithProjection(
                documentMapper,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper,
                projectionService
        );

        process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build());

        ArgumentCaptor<ChunkBgeM3> chunkCaptor = ArgumentCaptor.forClass(ChunkBgeM3.class);
        verify(chunkBgeM3Mapper).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue())
                .extracting(
                        ChunkBgeM3::getTitleBm25Vector,
                        ChunkBgeM3::getContentBm25Vector,
                        ChunkBgeM3::getBm25IndexVersion
                )
                .containsExactly("{11:2}", "{42:1}", 1);
    }

    @Test
    void shouldEmbedBeforeWritingBm25Projection() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        VchordBm25ProjectionService projectionService = mock(VchordBm25ProjectionService.class);
        Path storedFile = temporaryDirectory.resolve("notes.md");
        Files.writeString(storedFile, "# 标题\n正文");
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("notes.md")
                .filetype("md")
                .metadata("{\"filePath\":\"kb-1/doc-1/notes.md\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/notes.md")).thenReturn(storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(section()));
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        when(projectionService.project(any(), any())).thenReturn(
                new VchordBm25ProjectionService.Projection("{11:2}", "{42:1}", 1)
        );
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenReturn(1);
        Object processor = processorWithProjection(
                documentMapper,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper,
                projectionService
        );

        process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build());

        InOrder orderedWrites = inOrder(ragService, projectionService, chunkBgeM3Mapper);
        orderedWrites.verify(ragService).embed(any());
        orderedWrites.verify(projectionService).project(any(), any());
        orderedWrites.verify(chunkBgeM3Mapper).insert(any(ChunkBgeM3.class));
    }

    @Test
    void shouldIndexPlainTextAsOneDocumentChunkWithoutMarkdownHeadings() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        DocumentAssetMapper documentAssetMapper = mock(DocumentAssetMapper.class);
        Path storedFile = temporaryDirectory.resolve("notes.txt");
        Files.writeString(storedFile, "plain text");
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("notes.txt")
                .filetype("txt")
                .metadata("{\"filePath\":\"kb-1/doc-1/notes.txt\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/notes.txt")).thenReturn(storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(ragService.embed("notes\nnotes\nplain text")).thenReturn(new float[]{0.1F, 0.2F});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenReturn(1);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                new MarkdownParserServiceImpl(),
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper
        );

        process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build());

        ArgumentCaptor<ChunkBgeM3> chunkCaptor = ArgumentCaptor.forClass(ChunkBgeM3.class);
        verify(chunkBgeM3Mapper).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue().getContent()).isEqualTo("plain text");
        verifyNoInteractions(documentAssetMapper);
    }

    @Test
    void shouldIndexMarkdownAsOneDocumentChunkWithoutHeadings() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        DocumentAssetMapper documentAssetMapper = mock(DocumentAssetMapper.class);
        Path storedFile = temporaryDirectory.resolve("notes.md");
        Files.writeString(storedFile, "plain markdown text");
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("notes.md")
                .filetype("md")
                .metadata("{\"filePath\":\"kb-1/doc-1/notes.md\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/notes.md")).thenReturn(storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(ragService.embed("notes\nnotes\nplain markdown text")).thenReturn(new float[]{0.1F, 0.2F});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenReturn(1);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                new MarkdownParserServiceImpl(),
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper
        );

        process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build());

        ArgumentCaptor<ChunkBgeM3> chunkCaptor = ArgumentCaptor.forClass(ChunkBgeM3.class);
        verify(chunkBgeM3Mapper).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue().getContent()).isEqualTo("plain markdown text");
        verify(documentAssetMapper).deleteByDocumentId("doc-1");
    }

    @Test
    void shouldNotPersistAssetsForHtmlDocument() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        DocumentAssetMapper documentAssetMapper = mock(DocumentAssetMapper.class);
        Path storedFile = temporaryDirectory.resolve("guide.html");
        Files.writeString(storedFile, "<html><body><h1>HTML Guide</h1><p>正文</p></body></html>");
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("guide.html")
                .filetype("html")
                .metadata("{\"filePath\":\"kb-1/doc-1/guide.html\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/guide.html")).thenReturn(storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenReturn(1);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                new MarkdownParserServiceImpl(),
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper
        );

        process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build());

        verifyNoInteractions(documentAssetMapper);
    }

    @Test
    void shouldFailWhenAChunkInsertDoesNotPersist() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        Path storedFile = temporaryDirectory.resolve("notes.md");
        Files.writeString(storedFile, "# 标题\n正文");
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("notes.md")
                .filetype("md")
                .metadata("{\"filePath\":\"kb-1/doc-1/notes.md\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/notes.md")).thenReturn(storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(section()));
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenReturn(0);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build())
        ).hasCauseInstanceOf(com.kama.jchatmind.exception.BizException.class);
    }

    @Test
    void shouldPersistPdfPageAssetsAndLinkThemToGeneratedChunks() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        DocumentAssetMapper documentAssetMapper = mock(DocumentAssetMapper.class);
        Path storedFile = temporaryDirectory.resolve("guide.pdf");
        Files.write(storedFile, new byte[]{'%', 'P', 'D', 'F'});
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("guide.pdf")
                .filetype("pdf")
                .metadata("{\"filePath\":\"kb-1/doc-1/guide.pdf\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/guide.pdf")).thenReturn(storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(markdownParserService.parsePdf(any())).thenReturn(List.of(
                section("第 1 页", "第一页正文", 1),
                section("第 2 页", "第二页正文", 2)
        ));
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        AtomicInteger chunkSequence = new AtomicInteger(1);
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenAnswer(invocation -> {
            ChunkBgeM3 chunk = invocation.getArgument(0);
            chunk.setId("00000000-0000-0000-0000-00000000a20" + chunkSequence.getAndIncrement());
            return 1;
        });
        when(documentAssetMapper.insert(any(DocumentAsset.class))).thenReturn(1);
        when(documentAssetMapper.insertChunkRelation(any(), any(), any(), any())).thenReturn(1);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper
        );

        process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build());

        ArgumentCaptor<ChunkBgeM3> chunkCaptor = ArgumentCaptor.forClass(ChunkBgeM3.class);
        verify(chunkBgeM3Mapper, org.mockito.Mockito.times(2)).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getAllValues())
                .extracting(ChunkBgeM3::getMetadata)
                .allMatch(metadata -> metadata.contains("pageNumber"));
        ArgumentCaptor<DocumentAsset> assetCaptor = ArgumentCaptor.forClass(DocumentAsset.class);
        verify(documentAssetMapper, org.mockito.Mockito.times(2)).insert(assetCaptor.capture());
        assertThat(assetCaptor.getAllValues())
                .extracting(
                        DocumentAsset::getDocumentId,
                        DocumentAsset::getAssetType,
                        DocumentAsset::getAssetKey,
                        DocumentAsset::getPageNumber,
                        DocumentAsset::getLocator,
                        DocumentAsset::getContentHash,
                        DocumentAsset::getParserVersion,
                        DocumentAsset::getStatus
                )
                .containsExactly(
                        tuple("doc-1", "PDF_PAGE_TEXT", "page-1", 1, "{\"pageNumber\":1}", sha256("第一页正文"), "pdf-text-v1", "READY"),
                        tuple("doc-1", "PDF_PAGE_TEXT", "page-2", 2, "{\"pageNumber\":2}", sha256("第二页正文"), "pdf-text-v1", "READY")
                );
        ArgumentCaptor<String> assetIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> chunkIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> assetDocumentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> chunkDocumentIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentAssetMapper, org.mockito.Mockito.times(2)).insertChunkRelation(
                assetIdCaptor.capture(),
                chunkIdCaptor.capture(),
                assetDocumentIdCaptor.capture(),
                chunkDocumentIdCaptor.capture()
        );
        assertThat(assetIdCaptor.getAllValues())
                .containsExactlyElementsOf(assetCaptor.getAllValues().stream().map(DocumentAsset::getAssetId).toList());
        assertThat(chunkIdCaptor.getAllValues())
                .containsExactly("00000000-0000-0000-0000-00000000a201", "00000000-0000-0000-0000-00000000a202");
        assertThat(assetDocumentIdCaptor.getAllValues()).containsOnly("doc-1");
        assertThat(chunkDocumentIdCaptor.getAllValues()).containsOnly("doc-1");
        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(List.of(
                objectMapper.readTree(chunkCaptor.getAllValues().get(0).getMetadata()).path("asset").path("id").asText(),
                objectMapper.readTree(chunkCaptor.getAllValues().get(1).getMetadata()).path("asset").path("id").asText()
        )).containsExactlyElementsOf(assetIdCaptor.getAllValues());
        assertThat(List.of(
                objectMapper.readTree(chunkCaptor.getAllValues().get(0).getMetadata()).path("asset").path("type").asText(),
                objectMapper.readTree(chunkCaptor.getAllValues().get(1).getMetadata()).path("asset").path("type").asText()
        )).containsExactly("PDF_PAGE_TEXT", "PDF_PAGE_TEXT");
        verify(documentAssetMapper).deleteByDocumentId("doc-1");
        verify(markdownParserService).parsePdf(any());
    }

    @Test
    void shouldPersistMarkdownTableAssetAndLinkItToGeneratedChunk() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        DocumentAssetMapper documentAssetMapper = mock(DocumentAssetMapper.class);
        Path storedFile = temporaryDirectory.resolve("deployment.md");
        String tableContent = "| 指标 | 值 |\n| --- | --- |\n| 超时 | 30s |";
        Files.writeString(storedFile, "# 发布配置\n" + tableContent, StandardCharsets.UTF_8);
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("deployment.md")
                .filetype("md")
                .metadata("{\"filePath\":\"kb-1/doc-1/deployment.md\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/deployment.md")).thenReturn(storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenAnswer(invocation -> {
            invocation.<ChunkBgeM3>getArgument(0).setId("00000000-0000-0000-0000-00000000a301");
            return 1;
        });
        when(documentAssetMapper.insert(any(DocumentAsset.class))).thenReturn(1);
        when(documentAssetMapper.insertChunkRelation(any(), any(), any(), any())).thenReturn(1);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                new MarkdownParserServiceImpl(),
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper
        );

        process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build());

        ArgumentCaptor<DocumentAsset> assetCaptor = ArgumentCaptor.forClass(DocumentAsset.class);
        verify(documentAssetMapper).insert(assetCaptor.capture());
        assertThat(assetCaptor.getValue())
                .extracting(
                        DocumentAsset::getDocumentId,
                        DocumentAsset::getAssetType,
                        DocumentAsset::getAssetKey,
                        DocumentAsset::getPageNumber,
                        DocumentAsset::getContentHash,
                        DocumentAsset::getParserVersion,
                        DocumentAsset::getStatus
                )
                .containsExactly("doc-1", "TABLE", "table-1", null, sha256(tableContent), "markdown-table-v1", "READY");
        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(assetCaptor.getValue().getLocator()).path("startLine").asInt()).isEqualTo(2);
        assertThat(objectMapper.readTree(assetCaptor.getValue().getLocator()).path("endLine").asInt()).isEqualTo(4);
        ArgumentCaptor<ChunkBgeM3> chunkCaptor = ArgumentCaptor.forClass(ChunkBgeM3.class);
        verify(chunkBgeM3Mapper).insert(chunkCaptor.capture());
        assertThat(objectMapper.readTree(chunkCaptor.getValue().getMetadata()).path("asset").path("id").asText())
                .isEqualTo(assetCaptor.getValue().getAssetId());
        assertThat(objectMapper.readTree(chunkCaptor.getValue().getMetadata()).path("asset").path("type").asText())
                .isEqualTo("TABLE");
        assertThat(assetCaptor.getValue().getCreatedAt()).isEqualTo(chunkCaptor.getValue().getCreatedAt());
        assertThat(assetCaptor.getValue().getUpdatedAt()).isEqualTo(chunkCaptor.getValue().getUpdatedAt());
        verify(documentAssetMapper).deleteByDocumentId("doc-1");
        verify(documentAssetMapper).insertChunkRelation(
                assetCaptor.getValue().getAssetId(),
                "00000000-0000-0000-0000-00000000a301",
                "doc-1",
                "doc-1"
        );
    }

    @Test
    void shouldReusePdfPageAssetForMultipleChunksFromSamePage() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        DocumentAssetMapper documentAssetMapper = mock(DocumentAssetMapper.class);
        Path storedFile = temporaryDirectory.resolve("guide.pdf");
        Files.write(storedFile, new byte[]{'%', 'P', 'D', 'F'});
        stubPdfDocument(documentMapper, documentStorageService, storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(markdownParserService.parsePdf(any())).thenReturn(List.of(
                section("第 1 页", "第一页上半段", 1),
                section("第 1 页", "第一页下半段", 1)
        ));
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        AtomicInteger chunkIndex = new AtomicInteger(1);
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenAnswer(invocation -> {
            invocation.<ChunkBgeM3>getArgument(0).setId(
                    "00000000-0000-0000-0000-00000000021" + chunkIndex.getAndIncrement()
            );
            return 1;
        });
        when(documentAssetMapper.insert(any(DocumentAsset.class))).thenReturn(1);
        when(documentAssetMapper.insertChunkRelation(any(), any(), any(), any())).thenReturn(1);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper
        );

        process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build());

        ArgumentCaptor<DocumentAsset> assetCaptor = ArgumentCaptor.forClass(DocumentAsset.class);
        verify(documentAssetMapper).insert(assetCaptor.capture());
        assertThat(assetCaptor.getValue())
                .extracting(
                        DocumentAsset::getAssetType,
                        DocumentAsset::getAssetKey,
                        DocumentAsset::getPageNumber,
                        DocumentAsset::getContentHash
                )
                .containsExactly("PDF_PAGE_TEXT", "page-1", 1, sha256("第一页上半段\n第一页下半段"));
        verify(documentAssetMapper, org.mockito.Mockito.times(2)).insertChunkRelation(
                org.mockito.ArgumentMatchers.eq(assetCaptor.getValue().getAssetId()),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("doc-1"),
                org.mockito.ArgumentMatchers.eq("doc-1")
        );
    }

    @Test
    void shouldFailWhenPdfPageAssetDoesNotPersist() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        DocumentAssetMapper documentAssetMapper = mock(DocumentAssetMapper.class);
        Path storedFile = temporaryDirectory.resolve("guide.pdf");
        Files.write(storedFile, new byte[]{'%', 'P', 'D', 'F'});
        stubPdfDocument(documentMapper, documentStorageService, storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(markdownParserService.parsePdf(any())).thenReturn(List.of(section("第 1 页", "第一页正文", 1)));
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenAnswer(invocation -> {
            invocation.<ChunkBgeM3>getArgument(0).setId("00000000-0000-0000-0000-00000000a201");
            return 1;
        });
        when(documentAssetMapper.insert(any(DocumentAsset.class))).thenReturn(0);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build())
        ).hasCauseInstanceOf(com.kama.jchatmind.exception.BizException.class)
                .hasMessageContaining("文档资产写入失败");
        verify(documentAssetMapper, org.mockito.Mockito.never()).insertChunkRelation(any(), any(), any(), any());
    }

    @Test
    void shouldFailWhenPdfPageAssetChunkRelationDoesNotPersist() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
        RagService ragService = mock(RagService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        DocumentAssetMapper documentAssetMapper = mock(DocumentAssetMapper.class);
        Path storedFile = temporaryDirectory.resolve("guide.pdf");
        Files.write(storedFile, new byte[]{'%', 'P', 'D', 'F'});
        stubPdfDocument(documentMapper, documentStorageService, storedFile);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of());
        when(markdownParserService.parsePdf(any())).thenReturn(List.of(section("第 1 页", "第一页正文", 1)));
        when(ragService.embed(any())).thenReturn(new float[]{0.1F, 0.2F});
        when(chunkBgeM3Mapper.insert(any(ChunkBgeM3.class))).thenAnswer(invocation -> {
            invocation.<ChunkBgeM3>getArgument(0).setId("00000000-0000-0000-0000-00000000a201");
            return 1;
        });
        when(documentAssetMapper.insert(any(DocumentAsset.class))).thenReturn(1);
        when(documentAssetMapper.insertChunkRelation(any(), any(), any(), any())).thenReturn(0);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build())
        ).hasCauseInstanceOf(com.kama.jchatmind.exception.BizException.class)
                .hasMessageContaining("文档资产关联写入失败");
    }

    @Test
    void shouldTurnCorruptPdfIntoStableBusinessFailure() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        Path storedFile = temporaryDirectory.resolve("broken.pdf");
        Files.write(storedFile, new byte[]{1, 2, 3});
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("broken.pdf")
                .filetype("pdf")
                .metadata("{\"filePath\":\"kb-1/doc-1/broken.pdf\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/broken.pdf")).thenReturn(storedFile);
        Object processor = processor(
                documentMapper,
                documentStorageService,
                new MarkdownParserServiceImpl(),
                mock(RagService.class),
                chunkBgeM3Mapper
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> process(processor, IngestionTask.builder().id("task-1").kbId("kb-1").documentId("doc-1").build())
        ).hasCauseInstanceOf(com.kama.jchatmind.exception.BizException.class)
                .hasMessageContaining("PDF 解析失败");
    }

    @Test
    void shouldDeclareTransactionForChunkReplacement() throws Exception {
        Method process = Class.forName("com.kama.jchatmind.ingestion.DefaultIngestionTaskProcessor")
                .getMethod("process", IngestionTask.class);

        assertThat(process.getAnnotation(Transactional.class)).isNotNull();
    }

    private Object processor(
            DocumentMapper documentMapper,
            DocumentStorageService documentStorageService,
            MarkdownParserService markdownParserService,
            RagService ragService,
            ChunkBgeM3Mapper chunkBgeM3Mapper
    ) {
        return processor(
                documentMapper,
                documentStorageService,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper,
                mock(DocumentAssetMapper.class)
        );
    }

    private Object processorWithProjection(
            DocumentMapper documentMapper,
            DocumentStorageService documentStorageService,
            MarkdownParserService markdownParserService,
            RagService ragService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            VchordBm25ProjectionService projectionService
    ) {
        try {
            Class<?> processorType = Class.forName("com.kama.jchatmind.ingestion.DefaultIngestionTaskProcessor");
            return processorType.getConstructor(
                    DocumentMapper.class,
                    DocumentStorageService.class,
                    ObjectMapper.class,
                    MarkdownParserService.class,
                    RagService.class,
                    ChunkBgeM3Mapper.class,
                    DocumentAssetMapper.class,
                    VchordBm25ProjectionService.class
            ).newInstance(
                    documentMapper,
                    documentStorageService,
                    new ObjectMapper(),
                    markdownParserService,
                    ragService,
                    chunkBgeM3Mapper,
                    mock(DocumentAssetMapper.class),
                    projectionService
            );
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G2 VectorChord 投影尚未接入默认摄入处理器", e);
        }
    }

    private Object processor(
            DocumentMapper documentMapper,
            DocumentStorageService documentStorageService,
            MarkdownParserService markdownParserService,
            RagService ragService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            DocumentAssetMapper documentAssetMapper
    ) {
        VchordBm25ProjectionService projectionService = mock(VchordBm25ProjectionService.class);
        when(projectionService.project(any(), any())).thenReturn(
                new VchordBm25ProjectionService.Projection(null, null, null)
        );
        try {
            Class<?> processorType = Class.forName("com.kama.jchatmind.ingestion.DefaultIngestionTaskProcessor");
            return processorType.getConstructor(
                    DocumentMapper.class,
                    DocumentStorageService.class,
                    ObjectMapper.class,
                    MarkdownParserService.class,
                    RagService.class,
                    ChunkBgeM3Mapper.class,
                    DocumentAssetMapper.class,
                    VchordBm25ProjectionService.class
            ).newInstance(
                    documentMapper,
                    documentStorageService,
                    new ObjectMapper(),
                    markdownParserService,
                    ragService,
                    chunkBgeM3Mapper,
                    documentAssetMapper,
                    projectionService
            );
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 默认摄入处理器尚未实现", e);
        }
    }

    private void process(Object processor, IngestionTask task) {
        try {
            Method process = processor.getClass().getMethod("process", IngestionTask.class);
            process.invoke(processor, task);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 摄入处理入口尚未实现", e);
        }
    }

    private MarkdownParserService.MarkdownSection section() {
        return section("标题", "正文", null);
    }

    private void stubPdfDocument(
            DocumentMapper documentMapper,
            DocumentStorageService documentStorageService,
            Path storedFile
    ) {
        when(documentMapper.selectById("doc-1")).thenReturn(Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .filename("guide.pdf")
                .filetype("pdf")
                .metadata("{\"filePath\":\"kb-1/doc-1/guide.pdf\"}")
                .build());
        when(documentStorageService.getFilePath("kb-1/doc-1/guide.pdf")).thenReturn(storedFile);
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private MarkdownParserService.MarkdownSection section(String title, String content, Integer pageNumber) {
        return new MarkdownParserService.MarkdownSection(
                title,
                content,
                title,
                "",
                1,
                false,
                MarkdownParserService.SectionType.LEAF_CONTENT,
                1,
                content.length(),
                pageNumber
        );
    }
}
