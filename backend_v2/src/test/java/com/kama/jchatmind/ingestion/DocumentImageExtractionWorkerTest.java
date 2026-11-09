package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.DocumentAssetExtractionMapper;
import com.kama.jchatmind.model.dto.DocumentImageAssetCandidate;
import com.kama.jchatmind.model.entity.DocumentAssetExtraction;
import com.kama.jchatmind.service.DocumentStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentImageExtractionWorkerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldSkipEntirelyWhenDisabled() {
        DocumentAssetExtractionMapper extractionMapper = mock(DocumentAssetExtractionMapper.class);
        DocumentImageExtractionWorker worker = worker(extractionMapper, mock(DocumentImageExtractionClient.class));
        ReflectionTestUtils.setField(worker, "enabled", false);

        worker.processPendingImages();

        verifyNoInteractions(extractionMapper);
    }

    @Test
    void shouldExtractAndPersistPendingImageAsset() throws Exception {
        Path derivedFile = prepareDerivedImage();
        DocumentAssetExtractionMapper extractionMapper = mock(DocumentAssetExtractionMapper.class);
        when(extractionMapper.selectImageAssetsWithoutActiveExtraction(anyInt(), anyInt()))
                .thenReturn(List.of(candidate()));
        when(extractionMapper.selectMaxAttemptCount("asset-1")).thenReturn(0);
        when(extractionMapper.insert(any(DocumentAssetExtraction.class))).thenReturn(1);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        when(documentStorageService.getFilePath("kb-1/doc-1/derived/image-x.png")).thenReturn(derivedFile);
        DocumentImageExtractionClient client = mock(DocumentImageExtractionClient.class);
        when(client.extract(anyString(), any())).thenReturn(validJson());
        DocumentImageExtractionWorker worker = worker(extractionMapper, client, documentStorageService);
        ReflectionTestUtils.setField(worker, "enabled", true);

        worker.processPendingImages();

        ArgumentCaptor<DocumentAssetExtraction> extractionCaptor = ArgumentCaptor.forClass(DocumentAssetExtraction.class);
        verify(extractionMapper).insert(extractionCaptor.capture());
        assertThat(extractionCaptor.getValue().getAttemptCount()).isEqualTo(1);
        assertThat(extractionCaptor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(extractionCaptor.getValue().getParserVersion())
                .isEqualTo(DocumentImageExtractionWorker.PARSER_VERSION);
        verify(extractionMapper, never()).markFailed(anyString(), any(), any());
        verify(persistenceServiceOf(worker)).persist(any(), any(), any());
    }

    @Test
    void shouldMarkFailedWhenModelCallFails() throws Exception {
        Path derivedFile = prepareDerivedImage();
        DocumentAssetExtractionMapper extractionMapper = mock(DocumentAssetExtractionMapper.class);
        when(extractionMapper.selectImageAssetsWithoutActiveExtraction(anyInt(), anyInt()))
                .thenReturn(List.of(candidate()));
        when(extractionMapper.selectMaxAttemptCount("asset-1")).thenReturn(1);
        when(extractionMapper.insert(any(DocumentAssetExtraction.class))).thenReturn(1);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        when(documentStorageService.getFilePath("kb-1/doc-1/derived/image-x.png")).thenReturn(derivedFile);
        DocumentImageExtractionClient client = mock(DocumentImageExtractionClient.class);
        when(client.extract(anyString(), any()))
                .thenThrow(new com.kama.jchatmind.exception.BizException("模型调用失败"));
        DocumentImageExtractionWorker worker = worker(extractionMapper, client, documentStorageService);
        ReflectionTestUtils.setField(worker, "enabled", true);

        worker.processPendingImages();

        ArgumentCaptor<DocumentAssetExtraction> extractionCaptor = ArgumentCaptor.forClass(DocumentAssetExtraction.class);
        verify(extractionMapper).insert(extractionCaptor.capture());
        assertThat(extractionCaptor.getValue().getAttemptCount()).isEqualTo(2);
        verify(extractionMapper).markFailed(eq(extractionCaptor.getValue().getExtractionId()), any(), any());
    }

    @Test
    void shouldMarkFailedWhenDerivedImageMissing() {
        DocumentAssetExtractionMapper extractionMapper = mock(DocumentAssetExtractionMapper.class);
        when(extractionMapper.selectImageAssetsWithoutActiveExtraction(anyInt(), anyInt()))
                .thenReturn(List.of(candidate()));
        when(extractionMapper.selectMaxAttemptCount("asset-1")).thenReturn(0);
        when(extractionMapper.insert(any(DocumentAssetExtraction.class))).thenReturn(1);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        when(documentStorageService.getFilePath(anyString()))
                .thenReturn(temporaryDirectory.resolve("missing").resolve("image-x.png"));
        DocumentImageExtractionWorker worker =
                worker(extractionMapper, mock(DocumentImageExtractionClient.class), documentStorageService);
        ReflectionTestUtils.setField(worker, "enabled", true);

        worker.processPendingImages();

        verify(extractionMapper).markFailed(anyString(), any(), any());
    }

    private Path prepareDerivedImage() throws Exception {
        Path derivedDir = Files.createDirectories(
                temporaryDirectory.resolve("kb-1").resolve("doc-1").resolve("derived"));
        Path derivedFile = derivedDir.resolve("image-x.png");
        Files.write(derivedFile, new byte[]{1, 2, 3});
        return derivedFile;
    }

    private DocumentImageAssetCandidate candidate() {
        DocumentImageAssetCandidate candidate = new DocumentImageAssetCandidate();
        candidate.setAssetId("asset-1");
        candidate.setDocumentId("doc-1");
        candidate.setKbId("kb-1");
        candidate.setContentHash("a".repeat(64));
        candidate.setPageNumber(1);
        candidate.setLocator("{\"source\":\"PDF\",\"pageNumber\":1,\"derivedImage\":\"kb-1/doc-1/derived/image-x.png\"}");
        candidate.setFilename("report.pdf");
        candidate.setFiletype("pdf");
        return candidate;
    }

    private String validJson() {
        return """
                {"caption": "架构图", "ocrText": "A->B", "summary": "s", "visualKind": "DIAGRAM",
                 "confidence": 0.9,
                 "entities": [{"key": "a", "name": "A", "type": "SERVICE"}],
                 "relations": [{"subject": "a", "predicate": "CALLS", "object": "a"}]}
                """;
    }

    private VisualExtractionPersistenceService persistenceServiceOf(DocumentImageExtractionWorker worker) {
        return (VisualExtractionPersistenceService) ReflectionTestUtils.getField(worker, "persistenceService");
    }

    private DocumentImageExtractionWorker worker(
            DocumentAssetExtractionMapper extractionMapper,
            DocumentImageExtractionClient client
    ) {
        return worker(extractionMapper, client, mock(DocumentStorageService.class));
    }

    private DocumentImageExtractionWorker worker(
            DocumentAssetExtractionMapper extractionMapper,
            DocumentImageExtractionClient client,
            DocumentStorageService documentStorageService
    ) {
        DocumentImageExtractionWorker worker = new DocumentImageExtractionWorker(
                extractionMapper,
                documentStorageService,
                client,
                new VisualExtractionResultParser(new ObjectMapper()),
                mock(VisualExtractionPersistenceService.class),
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(worker, "maxAttempts", 3);
        ReflectionTestUtils.setField(worker, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(worker, "batchSize", 1);
        return worker;
    }
}