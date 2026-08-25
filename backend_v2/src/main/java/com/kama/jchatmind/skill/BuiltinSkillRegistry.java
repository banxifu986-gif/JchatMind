package com.kama.jchatmind.skill;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class BuiltinSkillRegistry {

    private static final String TECHNICAL_DECISION_COMPARISON = "technical-decision-comparison";
    private static final Map<String, SkillDefinition> DEFINITIONS = Map.of(
            TECHNICAL_DECISION_COMPARISON,
            new SkillDefinition(
                    TECHNICAL_DECISION_COMPARISON,
                    "v1",
                    "技术方案对比",
                    List.of("question", "kbIds"),
                    List.of("KnowledgeTool"),
                    SkillExecutionMode.SYNC,
                    30,
                    2,
                    false
            )
    );

    public PreparedSkillInvocation prepare(
            String skillId,
            Map<String, Object> input,
            List<String> authorizedKnowledgeBaseIds
    ) {
        SkillDefinition definition = DEFINITIONS.get(skillId);
        if (definition == null) {
            throw new IllegalArgumentException("未登记的内置 Skill：" + skillId);
        }
        if (input == null) {
            throw new IllegalArgumentException("Skill 输入不能为空");
        }
        if (input.containsKey("tools")) {
            throw new IllegalArgumentException("Skill 输入不允许指定工具");
        }

        Set<String> supportedFields = Set.copyOf(definition.inputFields());
        for (String field : input.keySet()) {
            if (!supportedFields.contains(field)) {
                throw new IllegalArgumentException("Skill 输入包含未定义字段：" + field);
            }
        }

        String question = asRequiredText(input.get("question"), "question");
        List<String> authorizedScope = normalizeKnowledgeBaseIds(authorizedKnowledgeBaseIds);
        if (authorizedScope.isEmpty()) {
            throw new IllegalArgumentException("当前调用没有可用的知识库授权范围");
        }
        List<String> requestedScope = input.containsKey("kbIds")
                ? normalizeRequestedKnowledgeBaseIds(input.get("kbIds"))
                : authorizedScope;
        if (!authorizedScope.containsAll(requestedScope)) {
            throw new IllegalArgumentException("Skill 输入包含授权范围外的知识库");
        }
        if (requestedScope.isEmpty()) {
            throw new IllegalArgumentException("Skill 至少需要一个可访问的知识库");
        }

        Map<String, Object> normalizedInput = new LinkedHashMap<>();
        normalizedInput.put("question", question);
        normalizedInput.put("kbIds", requestedScope);
        return new PreparedSkillInvocation(definition, Map.copyOf(normalizedInput), List.copyOf(requestedScope));
    }

    public SkillOutputValidationResult validateOutput(
            PreparedSkillInvocation invocation,
            Map<String, Object> output
    ) {
        if (invocation == null) {
            throw new IllegalArgumentException("Skill 调用上下文不能为空");
        }
        if (output == null) {
            return SkillOutputValidationResult.invalid("Skill 输出不能为空");
        }

        if (Boolean.TRUE.equals(output.get("abstained"))) {
            return StringUtils.hasText(asText(output.get("reason")))
                    ? SkillOutputValidationResult.accepted()
                    : SkillOutputValidationResult.invalid("拒答输出必须提供原因");
        }
        if (!StringUtils.hasText(asText(output.get("conclusion")))) {
            return SkillOutputValidationResult.invalid("Skill 输出必须包含结论");
        }

        Object evidenceValue = output.get("evidence");
        if (!(evidenceValue instanceof List<?> evidence) || evidence.isEmpty()) {
            return SkillOutputValidationResult.invalid("回答必须提供至少一条知识库证据，或明确拒答原因");
        }

        List<String> violations = new ArrayList<>();
        for (Object item : evidence) {
            if (!(item instanceof Map<?, ?> evidenceItem)) {
                violations.add("每条证据必须是对象");
                continue;
            }
            String chunkId = asText(evidenceItem.get("chunkId"));
            String kbId = asText(evidenceItem.get("kbId"));
            if (!StringUtils.hasText(chunkId) || !StringUtils.hasText(kbId)) {
                violations.add("每条证据必须包含 chunkId 和 kbId");
            } else if (!invocation.knowledgeBaseIds().contains(kbId)) {
                violations.add("证据引用了调用范围外的知识库");
            }
        }
        return violations.isEmpty()
                ? SkillOutputValidationResult.accepted()
                : new SkillOutputValidationResult(false, List.copyOf(violations));
    }

    private List<String> normalizeKnowledgeBaseIds(List<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String knowledgeBaseId : knowledgeBaseIds) {
            if (StringUtils.hasText(knowledgeBaseId)) {
                normalized.add(knowledgeBaseId.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeRequestedKnowledgeBaseIds(Object rawValue) {
        if (!(rawValue instanceof List<?> values)) {
            throw new IllegalArgumentException("kbIds 必须是字符串数组");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String knowledgeBaseId) || !StringUtils.hasText(knowledgeBaseId)) {
                throw new IllegalArgumentException("kbIds 必须是非空字符串数组");
            }
            normalized.add(knowledgeBaseId.trim());
        }
        return List.copyOf(normalized);
    }

    private String asRequiredText(Object value, String fieldName) {
        String text = asText(value);
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Skill 输入缺少非空字段：" + fieldName);
        }
        return text.trim();
    }

    private String asText(Object value) {
        return value instanceof String text ? text : null;
    }
}

record SkillDefinition(
        String id,
        String version,
        String name,
        List<String> inputFields,
        List<String> allowedTools,
        SkillExecutionMode executionMode,
        int timeoutSeconds,
        int concurrencyLimit,
        boolean requiresApproval
) {
}

record PreparedSkillInvocation(
        SkillDefinition definition,
        Map<String, Object> input,
        List<String> knowledgeBaseIds
) {
}

record SkillOutputValidationResult(
        boolean valid,
        List<String> violations
) {
    static SkillOutputValidationResult accepted() {
        return new SkillOutputValidationResult(true, List.of());
    }

    static SkillOutputValidationResult invalid(String violation) {
        return new SkillOutputValidationResult(false, List.of(violation));
    }
}

enum SkillExecutionMode {
    SYNC,
    ASYNC
}
