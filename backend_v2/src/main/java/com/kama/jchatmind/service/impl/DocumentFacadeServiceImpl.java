package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final ObjectMapper objectMapper;
    private final DocumentStorageService documentStorageService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final RequestScopeData requestScopeData;
    private final IngestionTaskServiceImpl ingestionTaskService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public GetDocumentsResponse getDocuments() {
        List<String> kbIds = knowledgeBaseAccessService.getOwnedKnowledgeBases(requireUserId()).stream()
                .map(KnowledgeBase::getId)
                .toList();
        List<Document> documents = kbIds.isEmpty()
                ? List.of()
                : documentMapper.selectByKbIds(kbIds);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        knowledgeBaseAccessService.requireAccessibleKnowledgeBase(kbId, requireUserId());
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            knowledgeBaseAccessService.requireAccessibleKnowledgeBase(request.getKbId(), requireUserId());
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            log.error("创建文档序列化失败", e);
            throw new BizException("创建文档时发生序列化错误");
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, String idempotencyKey, MultipartFile file) {
        String filePath = null;
        String documentId = null;
        try {
            String userId = requireUserId();
            knowledgeBaseAccessService.requireAccessibleKnowledgeBase(kbId, userId);
            if (!StringUtils.hasText(idempotencyKey)) {
                throw new BizException("幂等键不能为空");
            }
            IngestionTask existingTask = ingestionTaskService
                    .findExistingDocumentIngestion(kbId, idempotencyKey);
            if (existingTask != null) {
                return CreateDocumentResponse.builder()
                        .documentId(existingTask.getDocumentId())
                        .taskId(existingTask.getId())
                        .build();
            }
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            documentId = document.getId();

            // 保存文件（事务外磁盘 IO，不占用数据库连接）
            filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata；与 ingestion 任务创建在同一事务提交，事务内不做磁盘 IO
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            final String submittedDocumentId = documentId;
            IngestionTask task = transactionTemplate.execute(txStatus -> {
                if (documentMapper.updateByIdAndOwnerId(updatedDocument, userId) <= 0) {
                    throw new BizException("更新文档失败");
                }
                return ingestionTaskService.submitDocumentIngestion(kbId, submittedDocumentId, idempotencyKey);
            });
            String taskId = task.getId();

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .taskId(taskId)
                    .build();
        } catch (IOException e) {
            compensateUpload(documentId, filePath);
            log.error("文件保存失败", e);
            throw new BizException("文件保存失败");
        } catch (RuntimeException e) {
            compensateUpload(documentId, filePath);
            throw e;
        }
    }

    private void compensateUpload(String documentId, String filePath) {
        if (StringUtils.hasText(filePath)) {
            try {
                documentStorageService.deleteFile(filePath);
            } catch (IOException cleanupException) {
                log.warn("上传失败后的文件补偿删除失败: path={}", filePath, cleanupException);
            }
        }
        if (StringUtils.hasText(documentId)) {
            try {
                documentMapper.deleteById(documentId);
            } catch (RuntimeException cleanupException) {
                log.warn("上传失败后的文档记录补偿删除失败: documentId={}", documentId, cleanupException);
            }
        }
    }

    @Override
    @Transactional
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("无权访问文档");
        }
        knowledgeBaseAccessService.requireAccessibleKnowledgeBase(document.getKbId(), requireUserId());

        chunkBgeM3Mapper.deleteByDocId(document.getId());

        // 删除数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }

        // 文件删除移到事务提交后执行，事务内不做磁盘 IO；文件删失败仅记日志，遗留孤儿文件可接受
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    DocumentDTO documentDTO = documentConverter.toDTO(document);
                    if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                        documentStorageService.deleteFile(documentDTO.getMetadata().getFilePath());
                    }
                } catch (Exception e) {
                    log.warn("删除文件失败，不影响删除结果: documentId={}, error={}", documentId, e.getMessage());
                }
            }
        });
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            String userId = requireUserId();
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("无权访问文档");
            }
            knowledgeBaseAccessService.requireAccessibleKnowledgeBase(existingDocument.getKbId(), userId);

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateByIdAndOwnerId(updatedDocument, userId);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            log.error("更新文档序列化失败", e);
            throw new BizException("更新文档时发生序列化错误");
        }
    }

    private String requireUserId() {
        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            throw new BizException("用户未登录");
        }
        return String.valueOf(userId);
    }
}
