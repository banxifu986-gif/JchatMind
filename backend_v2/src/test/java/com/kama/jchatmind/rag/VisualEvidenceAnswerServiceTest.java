package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.DocumentStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VisualEvidenceAnswerServiceTest {

    @TempDir
    Path temporaryDirectory;

    private final DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
    private final ImageEvidenceClient imageEvidenceClient = mock(ImageEvidenceClient.class);
    private final VisualEvidenceAnswerService service = new VisualEvidenceAnswerService(
            documentStorageService,
            imageEvidenceClient,
            new ObjectMapper()
    );

    @Test
    void shouldReturnNullWhenAnswerEnrichmentDisabled() {
        ReflectionTestUtils.setField(service, "answerEnabled", false);

        assertThat(service.enrich(List.of(imageResult()), 3)).isNull();
        verifyNoInteractions(documentStorageService, imageEvidenceClient);
    }

    @Test
    void shouldEnrichTopImageAssetsWithCitationRequirement() throws Exception {
        ReflectionTestUtils.setField(service, "answerEnabled", true);
        Path derivedDir = Files.createDirectories(
                temporaryDirectory.resolve("kb-1").resolve("doc-1").resolve("derived"));
        Path derivedFile = derivedDir.resolve("image-x.png");
        Files.write(derivedFile, new byte[]{1, 2, 3});
        when(documentStorageService.getFilePath("kb-1/doc-1/derived/image-x.png")).thenReturn(derivedFile);
        when(imageEvidenceClient.analyze(anyString(), any())).thenReturn("架构图描述：A 调用 B");

        String evidence = service.enrich(List.of(imageResult()), 3);

        assertThat(evidence)
                .contains("【图片证据 assetId=asset-1】")
                .contains("PDF 第 5 页")
                .contains("visualKind=DIAGRAM")
                .contains("架构图描述：A 调用 B")
                .contains("回答引用该图片时必须写 [assetId=asset-1]");
    }

    @Test
    void shouldSkipNonImageAndMissingDerivedImageResults() {
        ReflectionTestUtils.setField(service, "answerEnabled", true);
        when(documentStorageService.getFilePath(anyString()))
                .thenReturn(temporaryDirectory.resolve("missing.png"));

        assertThat(service.enrich(List.of(imageResult(), textResult()), 3)).isNull();
    }

    @Test
    void shouldSkipImageWhenModelCallFails() throws Exception {
        ReflectionTestUtils.setField(service, "answerEnabled", true);
        Path derivedDir = Files.createDirectories(
                temporaryDirectory.resolve("kb-1").resolve("doc-1").resolve("derived"));
        Path derivedFile = derivedDir.resolve("image-x.png");
        Files.write(derivedFile, new byte[]{1, 2, 3});
        when(documentStorageService.getFilePath("kb-1/doc-1/derived/image-x.png")).thenReturn(derivedFile);
        when(imageEvidenceClient.analyze(anyString(), any()))
                .thenThrow(new com.kama.jchatmind.exception.BizException("模型失败"));

        assertThat(service.enrich(List.of(imageResult()), 3)).isNull();
    }

    private RagRetrievalResult imageResult() {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId("chunk-1");
        result.setKbId("kb-1");
        result.setDocId("doc-1");
        result.setContent("架构图");
        result.setMetadata("""
                {"asset": {"id": "asset-1", "type": "IMAGE",
                 "locator": {"source": "PDF", "pageNumber": 5, "derivedImage": "kb-1/doc-1/derived/image-x.png"},
                 "visualKind": "DIAGRAM"}}
                """);
        return result;
    }

    private RagRetrievalResult textResult() {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId("chunk-2");
        result.setKbId("kb-1");
        result.setDocId("doc-1");
        result.setContent("正文");
        result.setMetadata("{}");
        return result;
    }
}