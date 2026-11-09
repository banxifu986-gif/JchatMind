package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;

@Component
public class VisualExtractionResultParser {

    private static final Set<String> VISUAL_KINDS = Set.of(
            "DIAGRAM", "CHART", "TABLE_IMAGE", "SCREENSHOT", "TEXT_IMAGE", "OTHER"
    );

    private final ObjectMapper objectMapper;

    public VisualExtractionResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public VisualExtractionResult parse(String rawJson) {
        VisualExtractionResult result;
        try {
            result = objectMapper.readValue(normalizeJson(rawJson), VisualExtractionResult.class);
        } catch (Exception e) {
            throw new BizException("视觉抽取结果 JSON 非法");
        }
        validate(result);
        return result;
    }

    private String normalizeJson(String rawJson) {
        String trimmed = rawJson.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = trimmed.indexOf('\n', fenceStart);
            int fenceEnd = trimmed.indexOf("```", contentStart);
            if (contentStart >= 0 && fenceEnd > contentStart) {
                trimmed = trimmed.substring(contentStart + 1, fenceEnd).trim();
            }
        }
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private void validate(VisualExtractionResult result) {
        if (result == null || !StringUtils.hasText(result.caption())) {
            throw new BizException("视觉抽取结果缺少 caption");
        }
        if (!VISUAL_KINDS.contains(result.visualKind())) {
            throw new BizException("视觉抽取结果 visualKind 非法");
        }
        if (result.confidence() < 0 || result.confidence() > 1) {
            throw new BizException("视觉抽取结果 confidence 越界");
        }
        validateEntities(result);
        validateRelations(result);
    }

    private void validateEntities(VisualExtractionResult result) {
        Set<String> keys = new HashSet<>();
        for (VisualExtractionResult.VisualEntity entity : result.entities()) {
            if (entity == null || !StringUtils.hasText(entity.key()) || !StringUtils.hasText(entity.name())) {
                throw new BizException("视觉抽取结果实体缺少 key 或 name");
            }
            if (!keys.add(entity.key())) {
                throw new BizException("视觉抽取结果实体 key 重复");
            }
            validateConfidence(entity.confidence(), "实体");
            validateBbox(entity.bbox(), "实体");
        }
    }

    private void validateRelations(VisualExtractionResult result) {
        Set<String> keys = new HashSet<>();
        for (VisualExtractionResult.VisualEntity entity : result.entities()) {
            keys.add(entity.key());
        }
        for (VisualExtractionResult.VisualRelation relation : result.relations()) {
            if (relation == null || !StringUtils.hasText(relation.subject())
                    || !StringUtils.hasText(relation.predicate()) || !StringUtils.hasText(relation.object())) {
                throw new BizException("视觉抽取结果关系字段缺失");
            }
            if (!keys.contains(relation.subject()) || !keys.contains(relation.object())) {
                throw new BizException("视觉抽取结果关系引用未声明实体");
            }
            validateConfidence(relation.confidence(), "关系");
        }
    }

    private void validateConfidence(Double confidence, String label) {
        if (confidence != null && (confidence < 0 || confidence > 1)) {
            throw new BizException("视觉抽取结果" + label + " confidence 越界");
        }
    }

    private void validateBbox(double[] bbox, String label) {
        if (bbox == null) {
            return;
        }
        if (bbox.length != 4) {
            throw new BizException("视觉抽取结果" + label + " bbox 必须为四元数组");
        }
    }
}