package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.DocumentStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
@Slf4j
public class VisualEvidenceAnswerService {

    private final DocumentStorageService documentStorageService;
    private final ImageEvidenceClient imageEvidenceClient;
    private final ObjectMapper objectMapper;

    @Value("${rag.visual-extraction.answer-enabled:false}")
    private boolean answerEnabled;

    public VisualEvidenceAnswerService(
            DocumentStorageService documentStorageService,
            ImageEvidenceClient imageEvidenceClient,
            ObjectMapper objectMapper
    ) {
        this.documentStorageService = documentStorageService;
        this.imageEvidenceClient = imageEvidenceClient;
        this.objectMapper = objectMapper;
    }

    public String enrich(List<RagRetrievalResult> results, int topK) {
        if (!answerEnabled || results == null || results.isEmpty() || topK <= 0) {
            return null;
        }
        StringBuilder evidence = new StringBuilder();
        int included = 0;
        for (RagRetrievalResult result : results) {
            if (included >= topK) {
                break;
            }
            String block = analyzeImageAsset(result);
            if (block == null) {
                continue;
            }
            if (evidence.length() > 0) {
                evidence.append("\n\n");
            }
            evidence.append(block);
            included++;
        }
        return evidence.length() == 0 ? null : evidence.toString();
    }

    private String analyzeImageAsset(RagRetrievalResult result) {
        JsonNode asset = readAsset(result);
        if (asset == null || !"IMAGE".equals(asset.path("type").asText())) {
            return null;
        }
        String assetId = asset.path("id").asText();
        JsonNode locator = asset.path("locator");
        String derivedImage = locator.path("derivedImage").asText();
        if (!StringUtils.hasText(assetId) || !StringUtils.hasText(derivedImage)) {
            return null;
        }
        Path derivedPath = documentStorageService.getFilePath(derivedImage);
        if (derivedPath == null || !Files.isRegularFile(derivedPath)) {
            log.warn("图片证据派生文件不存在，跳过读图: assetId={}", assetId);
            return null;
        }
        byte[] pngBytes;
        try {
            pngBytes = Files.readAllBytes(derivedPath);
        } catch (IOException e) {
            log.warn("读取图片证据派生文件失败，跳过读图: assetId={}", assetId);
            return null;
        }
        String analysis;
        try {
            analysis = imageEvidenceClient.analyze(buildPrompt(assetId, locator), pngBytes);
        } catch (RuntimeException e) {
            log.warn("图片证据模型调用失败，跳过读图: assetId={}", assetId, e);
            return null;
        }
        if (!StringUtils.hasText(analysis)) {
            return null;
        }
        StringBuilder block = new StringBuilder();
        block.append("【图片证据 assetId=").append(assetId).append("】");
        String location = resolveLocation(locator);
        if (StringUtils.hasText(location)) {
            block.append("（").append(location).append("）");
        }
        String visualKind = asset.path("visualKind").asText();
        if (StringUtils.hasText(visualKind)) {
            block.append(" visualKind=").append(visualKind);
        }
        block.append("\n").append(analysis.trim());
        block.append("\n回答引用该图片时必须写 [assetId=").append(assetId).append("]");
        return block.toString();
    }

    private JsonNode readAsset(RagRetrievalResult result) {
        if (result == null || !StringUtils.hasText(result.getMetadata())) {
            return null;
        }
        try {
            JsonNode metadata = objectMapper.readTree(result.getMetadata());
            JsonNode asset = metadata.path("asset");
            return asset.isObject() ? asset : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String buildPrompt(String assetId, JsonNode locator) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("图片 assetId：").append(assetId).append("\n");
        if (locator.hasNonNull("pageNumber")) {
            prompt.append("页码：").append(locator.get("pageNumber").asInt()).append("\n");
        }
        if (locator.hasNonNull("sectionPath")) {
            prompt.append("章节路径：").append(locator.get("sectionPath").asText()).append("\n");
        }
        prompt.append("请描述该图片内容并整理图中可见文本，供后续问答引用。");
        return prompt.toString();
    }

    private String resolveLocation(JsonNode locator) {
        if (locator.hasNonNull("sectionPath")) {
            return "章节 " + locator.get("sectionPath").asText();
        }
        if (locator.hasNonNull("pageNumber")) {
            return "PDF 第 " + locator.get("pageNumber").asInt() + " 页";
        }
        return null;
    }
}