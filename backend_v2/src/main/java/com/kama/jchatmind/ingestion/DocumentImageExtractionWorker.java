package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentAssetExtractionMapper;
import com.kama.jchatmind.model.dto.DocumentImageAssetCandidate;
import com.kama.jchatmind.model.entity.DocumentAssetExtraction;
import com.kama.jchatmind.service.DocumentStorageService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class DocumentImageExtractionWorker {

    static final String PARSER_VERSION = "visual-extraction-worker-v1";

    private final DocumentAssetExtractionMapper documentAssetExtractionMapper;
    private final DocumentStorageService documentStorageService;
    private final DocumentImageExtractionClient extractionClient;
    private final VisualExtractionResultParser resultParser;
    private final VisualExtractionPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    @Value("${rag.visual-extraction.enabled:false}")
    private boolean enabled;

    @Value("${rag.visual-extraction.max-attempts:3}")
    private int maxAttempts;

    @Value("${rag.visual-extraction.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${rag.visual-extraction.batch-size:1}")
    private int batchSize;

    private final ExecutorService extractionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "document-image-extraction");
        thread.setDaemon(true);
        return thread;
    });

    public DocumentImageExtractionWorker(
            DocumentAssetExtractionMapper documentAssetExtractionMapper,
            DocumentStorageService documentStorageService,
            DocumentImageExtractionClient extractionClient,
            VisualExtractionResultParser resultParser,
            VisualExtractionPersistenceService persistenceService,
            ObjectMapper objectMapper
    ) {
        this.documentAssetExtractionMapper = documentAssetExtractionMapper;
        this.documentStorageService = documentStorageService;
        this.extractionClient = extractionClient;
        this.resultParser = resultParser;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 30_000)
    public void processPendingImages() {
        if (!enabled) {
            return;
        }
        List<DocumentImageAssetCandidate> candidates =
                documentAssetExtractionMapper.selectImageAssetsWithoutActiveExtraction(maxAttempts, batchSize);
        for (DocumentImageAssetCandidate candidate : candidates) {
            processOne(candidate);
        }
    }

    private void processOne(DocumentImageAssetCandidate candidate) {
        Integer previousAttempts = documentAssetExtractionMapper.selectMaxAttemptCount(candidate.getAssetId());
        int attemptCount = (previousAttempts == null ? 0 : previousAttempts) + 1;
        LocalDateTime now = LocalDateTime.now();
        DocumentAssetExtraction extraction = DocumentAssetExtraction.builder()
                .extractionId(UUID.randomUUID().toString())
                .assetId(candidate.getAssetId())
                .documentId(candidate.getDocumentId())
                .visualKind("OTHER")
                .modelName(extractionClient.visionModel())
                .modelVersion("")
                .promptVersion("image-extraction-prompt-v1")
                .parserVersion(PARSER_VERSION)
                .attemptCount(attemptCount)
                .rawResult(null)
                .status("PENDING")
                .createdAt(now)
                .updatedAt(now)
                .build();
        if (documentAssetExtractionMapper.insert(extraction) <= 0) {
            throw new BizException("视觉抽取任务写入失败");
        }
        try {
            byte[] pngBytes = readDerivedImage(candidate);
            String rawJson = callModelWithTimeout(buildPrompt(candidate), pngBytes);
            extraction.setRawResult(rawJson);
            VisualExtractionResult result = resultParser.parse(rawJson);
            persistenceService.persist(candidate, extraction, result);
        } catch (Exception e) {
            documentAssetExtractionMapper.markFailed(extraction.getExtractionId(), extraction.getRawResult(), LocalDateTime.now());
            log.error("图片视觉抽取失败: assetId={}, attempt={}", candidate.getAssetId(), attemptCount, e);
        }
    }

    private byte[] readDerivedImage(DocumentImageAssetCandidate candidate) {
        try {
            JsonNode locator = objectMapper.readTree(candidate.getLocator());
            String derivedImage = locator.path("derivedImage").asText();
            if (!StringUtils.hasText(derivedImage)) {
                throw new BizException("图片资产缺少派生文件");
            }
            Path derivedPath = documentStorageService.getFilePath(derivedImage);
            if (derivedPath == null || !Files.isRegularFile(derivedPath)) {
                throw new BizException("图片派生文件不存在");
            }
            return Files.readAllBytes(derivedPath);
        } catch (JsonProcessingException e) {
            throw new BizException("图片资产定位信息非法");
        } catch (IOException e) {
            throw new BizException("读取图片派生文件失败");
        }
    }

    private String callModelWithTimeout(String prompt, byte[] pngBytes) throws InterruptedException {
        Future<String> future = extractionExecutor.submit(() -> extractionClient.extract(prompt, pngBytes));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BizException("视觉抽取超时");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException("视觉抽取模型调用失败: " + e.getCause());
        }
    }

    private String buildPrompt(DocumentImageAssetCandidate candidate) {
        try {
            JsonNode locator = objectMapper.readTree(candidate.getLocator());
            StringBuilder prompt = new StringBuilder();
            prompt.append("文档标题：").append(candidate.getFilename()).append("\n");
            if (locator.hasNonNull("sectionPath")) {
                prompt.append("章节路径：").append(locator.get("sectionPath").asText()).append("\n");
            }
            if (locator.hasNonNull("pageNumber")) {
                prompt.append("页码：").append(locator.get("pageNumber").asInt()).append("\n");
            }
            prompt.append("文件类型：").append(candidate.getFiletype()).append("\n");
            prompt.append("请分析附带的 PNG 图片并按系统要求输出 JSON。");
            return prompt.toString();
        } catch (JsonProcessingException e) {
            throw new BizException("图片资产定位信息非法");
        }
    }

    @PreDestroy
    void shutdown() {
        extractionExecutor.shutdownNow();
    }
}
