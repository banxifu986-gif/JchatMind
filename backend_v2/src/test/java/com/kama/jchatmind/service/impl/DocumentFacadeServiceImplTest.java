package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentFacadeServiceImplTest {

    @Test
    void shouldRejectReadingDocumentsFromForeignKnowledgeBase() {
        DocumentFacadeServiceImpl service = service(
                mock(DocumentMapper.class),
                mock(ChunkBgeM3Mapper.class),
                denyingAccessService()
        );

        assertThatThrownBy(() -> service.getDocumentsByKbId("foreign-kb"))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");
    }

    @Test
    void shouldRejectCreatingDocumentInForeignKnowledgeBase() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);
        DocumentFacadeServiceImpl service = service(
                documentMapper,
                mock(ChunkBgeM3Mapper.class),
                denyingAccessService()
        );
        CreateDocumentRequest request = new CreateDocumentRequest();
        request.setKbId("foreign-kb");
        request.setFilename("private.md");
        request.setFiletype("md");
        request.setSize(1L);

        assertThatThrownBy(() -> service.createDocument(request))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");
    }

    @Test
    void shouldRejectUploadingDocumentToForeignKnowledgeBase() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);
        DocumentFacadeServiceImpl service = service(
                documentMapper,
                mock(ChunkBgeM3Mapper.class),
                denyingAccessService()
        );
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("private.txt");
        when(file.getSize()).thenReturn(1L);

        assertThatThrownBy(() -> service.uploadDocument("foreign-kb", "upload-key", file))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");
    }

    @Test
    void shouldRejectUpdatingDocumentInForeignKnowledgeBase() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        Document document = Document.builder().id("doc-1").kbId("foreign-kb").build();
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);
        DocumentFacadeServiceImpl service = service(
                documentMapper,
                mock(ChunkBgeM3Mapper.class),
                denyingAccessService()
        );
        UpdateDocumentRequest request = new UpdateDocumentRequest();
        request.setFilename("renamed.md");

        assertThatThrownBy(() -> service.updateDocument("doc-1", request))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");
    }

    @Test
    void shouldDeleteChunksBeforeDeletingAuthorizedDocument() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        Document document = Document.builder().id("doc-1").kbId("owned-kb").build();
        ChunkBgeM3 chunk = ChunkBgeM3.builder().id("chunk-1").docId("doc-1").build();
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        when(documentMapper.deleteById("doc-1")).thenReturn(1);
        when(chunkBgeM3Mapper.selectByDocId("doc-1")).thenReturn(List.of(chunk));
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBase("owned-kb", "7"))
                .thenReturn(com.kama.jchatmind.model.entity.KnowledgeBase.builder()
                        .id("owned-kb")
                        .ownerId("7")
                        .build());
        DocumentFacadeServiceImpl service = service(documentMapper, chunkBgeM3Mapper, knowledgeBaseAccessService);

        service.deleteDocument("doc-1");

        verify(chunkBgeM3Mapper).deleteById("chunk-1");
    }

    @Test
    void shouldCreateIngestionTaskInsteadOfSynchronouslyIndexingUploadedDocument() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        IngestionTaskServiceImpl ingestionTaskService = mock(IngestionTaskServiceImpl.class);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("notes.md");
        when(file.getSize()).thenReturn(12L);
        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Document.class).setId("doc-1");
            return 1;
        });
        when(documentStorageService.saveFile("owned-kb", "doc-1", file))
                .thenReturn("owned-kb/doc-1/notes.md");
        when(ingestionTaskService.submitDocumentIngestion("owned-kb", "doc-1", "upload-key"))
                .thenReturn(IngestionTask.builder().id("task-1").build());
        Object service = asyncUploadService(
                documentMapper,
                documentStorageService,
                chunkBgeM3Mapper,
                allowingAccessService(),
                ingestionTaskService
        );

        Object response = uploadDocument(service, "owned-kb", "upload-key", file);

        assertThat(responseProperty(response, "getDocumentId")).isEqualTo("doc-1");
        assertThat(responseProperty(response, "getTaskId")).isEqualTo("task-1");
        verify(ingestionTaskService).submitDocumentIngestion("owned-kb", "doc-1", "upload-key");
        verifyNoInteractions(chunkBgeM3Mapper);
    }

    @Test
    void shouldRejectBlankIdempotencyKeyBeforeCreatingUploadedDocument() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentFacadeServiceImpl service = service(
                documentMapper,
                mock(ChunkBgeM3Mapper.class),
                allowingAccessService()
        );
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);

        assertThatThrownBy(() -> service.uploadDocument("owned-kb", " ", file))
                .isInstanceOf(BizException.class)
                .hasMessage("幂等键不能为空");
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void shouldReplayExistingUploadTaskBeforeCreatingAnotherDocumentOrFile() throws Exception {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService documentStorageService = mock(DocumentStorageService.class);
        IngestionTaskServiceImpl ingestionTaskService = mock(IngestionTaskServiceImpl.class);
        IngestionTask existingTask = IngestionTask.builder()
                .id("task-1")
                .kbId("owned-kb")
                .documentId("doc-1")
                .build();
        when(ingestionTaskService.findExistingDocumentIngestion("owned-kb", "upload-key"))
                .thenReturn(existingTask);
        Object service = asyncUploadService(
                documentMapper,
                documentStorageService,
                mock(ChunkBgeM3Mapper.class),
                allowingAccessService(),
                ingestionTaskService
        );
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);

        Object response = uploadDocument(service, "owned-kb", "upload-key", file);

        assertThat(responseProperty(response, "getDocumentId")).isEqualTo("doc-1");
        assertThat(responseProperty(response, "getTaskId")).isEqualTo("task-1");
        verify(documentMapper, never()).insert(any(Document.class));
        verifyNoInteractions(documentStorageService);
    }

    @Test
    void shouldKeepUploadIdempotencyLockUntilTaskCreationCompletes() throws Exception {
        Method uploadDocument = DocumentFacadeServiceImpl.class.getMethod(
                "uploadDocument",
                String.class,
                String.class,
                MultipartFile.class
        );

        assertThat(uploadDocument.getAnnotation(Transactional.class)).isNotNull();
    }

    private DocumentFacadeServiceImpl service(
            DocumentMapper documentMapper,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService
    ) {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        return new DocumentFacadeServiceImpl(
                documentMapper,
                new DocumentConverter(new ObjectMapper()),
                new ObjectMapper(),
                mock(DocumentStorageService.class),
                chunkBgeM3Mapper,
                knowledgeBaseAccessService,
                requestScopeData,
                mock(IngestionTaskServiceImpl.class)
        );
    }

    private KnowledgeBaseAccessService denyingAccessService() {
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBase("foreign-kb", "7"))
                .thenThrow(new BizException("无权访问知识库"));
        return knowledgeBaseAccessService;
    }

    private KnowledgeBaseAccessService allowingAccessService() {
        KnowledgeBaseAccessService knowledgeBaseAccessService = mock(KnowledgeBaseAccessService.class);
        when(knowledgeBaseAccessService.requireAccessibleKnowledgeBase("owned-kb", "7"))
                .thenReturn(com.kama.jchatmind.model.entity.KnowledgeBase.builder()
                        .id("owned-kb")
                        .ownerId("7")
                        .build());
        return knowledgeBaseAccessService;
    }

    private Object asyncUploadService(
            DocumentMapper documentMapper,
            DocumentStorageService documentStorageService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            IngestionTaskServiceImpl ingestionTaskService
    ) {
        try {
            return DocumentFacadeServiceImpl.class.getConstructor(
                    DocumentMapper.class,
                    DocumentConverter.class,
                    ObjectMapper.class,
                    DocumentStorageService.class,
                    ChunkBgeM3Mapper.class,
                    KnowledgeBaseAccessService.class,
                    RequestScopeData.class,
                    IngestionTaskServiceImpl.class
            ).newInstance(
                    documentMapper,
                    new DocumentConverter(new ObjectMapper()),
                    new ObjectMapper(),
                    documentStorageService,
                    chunkBgeM3Mapper,
                    knowledgeBaseAccessService,
                    requestScopeData(),
                    ingestionTaskService
            );
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 异步上传门面尚未实现", e);
        }
    }

    private Object uploadDocument(Object service, String kbId, String idempotencyKey, MultipartFile file) throws Exception {
        try {
            Method method = service.getClass().getMethod(
                    "uploadDocument",
                    String.class,
                    String.class,
                    MultipartFile.class
            );
            return method.invoke(service, kbId, idempotencyKey, file);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("G1 异步上传入口尚未实现", e);
        }
    }

    private Object responseProperty(Object response, String getterName) throws Exception {
        return response.getClass().getMethod(getterName).invoke(response);
    }

    private RequestScopeData requestScopeData() {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        return requestScopeData;
    }
}
