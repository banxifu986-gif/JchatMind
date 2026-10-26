package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryConsolidationProposal;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.kama.jchatmind.service.impl.UserMemoryWriteService.fingerprintMemories;

final class UserMemoryModelClient {

    private static final String MEMORY_EXTRACTION_SYSTEM_PROMPT = """
            你负责从对话中提取值得长期保存的用户信息。
            对每条提取的信息，输出包含以下字段的 JSON 对象：
            - scope: 持久性，取值为 persistent / current_task
            - type: 类型，取值为 BACKGROUND / PREFERENCE / CONSTRAINT / GOAL / FACT
            - content: 简洁的陈述句，不超过 120 字
            - importance: 重要程度，取值为 high / medium / low
            - confidence: 提取置信度，取值为 high / medium / low
            - should_persist: 是否应该写入长期记忆，取值 true / false
            - evidence_message_index: 来源消息在输入中的序号，从 0 开始

            规则：
            - 只提取稳定、可复用、对未来回答有帮助的信息
            - 一次性任务和短期上下文必须标记为 current_task，不能作为长期记忆保存
            - 不要保存敏感信息、纯闲聊信息
            - 不要提取可以从对话中直接推断出的通用信息
            - 如果与已有记忆存在冲突，content 使用"更新："前缀
            - 如果没有值得提取的信息，返回空数组 []

            只输出 JSON 数组，不要输出 markdown 代码块或解释文字。
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient memoryExtractionChatClient;

    UserMemoryModelClient(ObjectProvider<ChatClientRegistry> registryProvider) {
        this.memoryExtractionChatClient = resolveChatClient(registryProvider);
    }

    boolean isAvailable() {
        return memoryExtractionChatClient != null;
    }

    UserMemoryConsolidationProposal proposeConsolidation(String userId, List<UserMemory> sourceMemories) {
        if (memoryExtractionChatClient == null) {
            throw new BizException("记忆整理模型暂不可用");
        }
        String input = sourceMemories.stream()
                .map(memory -> "[" + sourceMemories.indexOf(memory) + "] [" + memory.getMemoryType() + "] " + memory.getContent())
                .collect(Collectors.joining("\n"));
        String response = memoryExtractionChatClient.prompt()
                .system("你负责为用户整理长期记忆。只合并内容相关且不会丢失关键差异的记忆。返回 JSON 对象，包含 source_indexes（至少两个输入序号）、memory_type（BACKGROUND/PREFERENCE/CONSTRAINT/GOAL/FACT）和 content（不超过120字）。只输出 JSON，不写解释。")
                .user("请提出一条合并建议，不要执行保存：\n" + input)
                .call()
                .content();
        if (!StringUtils.hasText(response)) {
            throw new BizException("记忆整理模型未返回建议");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode indexes = root.path("source_indexes");
            String memoryType = root.path("memory_type").asText("").toUpperCase(Locale.ROOT);
            String content = normalizeSegment(root.path("content").asText(""));
            if (!indexes.isArray() || indexes.size() < 2 || !isMemoryType(memoryType)
                    || !StringUtils.hasText(content) || content.length() > 300) {
                throw new IllegalStateException("consolidation proposal schema invalid");
            }
            List<UserMemory> selected = new ArrayList<>();
            for (JsonNode indexNode : indexes) {
                if (!indexNode.canConvertToInt()) {
                    throw new IllegalStateException("consolidation source index invalid");
                }
                int index = indexNode.asInt();
                if (index < 0 || index >= sourceMemories.size()) {
                    throw new IllegalStateException("consolidation source index out of range");
                }
                UserMemory source = sourceMemories.get(index);
                if (selected.stream().anyMatch(item -> item.getId().equals(source.getId()))) {
                    throw new IllegalStateException("duplicate consolidation source index");
                }
                selected.add(source);
            }
            List<String> sourceIds = selected.stream().map(UserMemory::getId).toList();
            UserMemoryConsolidationProposal proposal = UserMemoryConsolidationProposal.builder()
                    .userId(userId)
                    .sourceMemoryIdsJson(objectMapper.writeValueAsString(sourceIds))
                    .sourceFingerprint(fingerprintMemories(selected))
                    .proposedType(memoryType)
                    .proposedContent(content)
                    .build();
            return proposal;
        } catch (JsonProcessingException | IllegalStateException exception) {
            throw new BizException("记忆整理建议格式无效");
        }
    }

    private static ChatClient resolveChatClient(ObjectProvider<ChatClientRegistry> chatClientRegistryProvider) {
        if (chatClientRegistryProvider == null) {
            return null;
        }
        ChatClientRegistry registry = chatClientRegistryProvider.getIfAvailable();
        if (registry == null) {
            return null;
        }
        ChatClient client = registry.get("deepseek-chat");
        if (client == null) {
            for (ChatClient fallback : registry.getAllClients()) {
                if (fallback != null) {
                    return fallback;
                }
            }
        }
        return client;
    }

    List<ExtractedMemory> extract(List<UserMemory> memories, List<ChatMessageDTO> userMessages) {
        String existingMemoriesText = formatExistingMemories(memories);
        String messagesText = formatMessagesForExtraction(userMessages);
        String userPrompt = buildExtractionUserPrompt(existingMemoriesText, messagesText);
        String response = memoryExtractionChatClient.prompt()
                .system(MEMORY_EXTRACTION_SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();
        if (!StringUtils.hasText(response)) {
            throw new IllegalStateException("LLM memory extraction response is blank");
        }
        return parseExtractionResponse(response, userMessages);
    }

    private String formatExistingMemories(List<UserMemory> memories) {
        if (memories.isEmpty()) {
            return "无";
        }
        return memories.stream()
                .map(memory -> "- [" + memory.getMemoryType() + "] " + memory.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String formatMessagesForExtraction(List<ChatMessageDTO> userMessages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < userMessages.size(); i++) {
            sb.append("[").append(i).append("] ").append(userMessages.get(i).getContent()).append("\n");
        }
        return sb.toString();
    }

    private String buildExtractionUserPrompt(String existingMemories, String messagesText) {
        return "已有记忆（用于避免重复或判断冲突）：\n"
                + existingMemories
                + "\n\n对话消息（格式：[序号] 内容）：\n"
                + messagesText
                + "\n请从以上消息中提取值得长期保存的用户信息。";
    }

    private List<ExtractedMemory> parseExtractionResponse(String response, List<ChatMessageDTO> userMessages) {
        String json = response.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        if (!json.startsWith("[")) {
            throw new IllegalStateException("LLM memory extraction response must be a JSON array");
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                throw new IllegalStateException("LLM memory extraction response must be a JSON array");
            }

            List<ExtractedMemory> result = new ArrayList<>();
            for (JsonNode node : root) {
                String scope = getTextField(node, "scope");
                String type = getTextField(node, "type");
                String content = getTextField(node, "content");
                String importance = getTextField(node, "importance");
                String confidence = getTextField(node, "confidence");
                JsonNode shouldPersistNode = node.get("should_persist");
                JsonNode evidenceIndexNode = node.get("evidence_message_index");
                if (!node.isObject()
                        || !("persistent".equalsIgnoreCase(scope) || "current_task".equalsIgnoreCase(scope))
                        || !isMemoryType(type)
                        || !StringUtils.hasText(content)
                        || !isImportance(importance)
                        || !isImportance(confidence)
                        || shouldPersistNode == null
                        || !shouldPersistNode.isBoolean()
                        || evidenceIndexNode == null
                        || !evidenceIndexNode.canConvertToInt()) {
                    throw new IllegalStateException("LLM memory extraction item does not match the required schema");
                }
                boolean shouldPersist = shouldPersistNode.asBoolean();
                int evidenceIndex = evidenceIndexNode.asInt();
                if (evidenceIndex < 0 || evidenceIndex >= userMessages.size()) {
                    throw new IllegalStateException("LLM memory extraction evidence index is invalid");
                }
                if (content.length() > 300) {
                    content = content.substring(0, 300);
                }

                ChatMessageDTO sourceMsg = userMessages.get(evidenceIndex);
                if (!StringUtils.hasText(sourceMsg.getId())) {
                    throw new IllegalStateException("LLM memory extraction evidence message is missing an id");
                }
                String evidenceText = sourceMsg.getContent();
                if (evidenceText != null && evidenceText.length() > 500) {
                    evidenceText = evidenceText.substring(0, 500);
                }
                String effectiveScope = isTemporaryInstruction(evidenceText)
                        ? "CURRENT_TASK"
                        : scope.toUpperCase(Locale.ROOT);

                result.add(new ExtractedMemory(
                        effectiveScope,
                        type.toUpperCase(Locale.ROOT),
                        content,
                        normalizeImportance(importance),
                        normalizeImportance(confidence),
                        shouldPersist,
                        sourceMsg.getId(),
                        evidenceText != null ? evidenceText : content
                ));
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LLM memory extraction response is invalid", e);
        }
    }

    private boolean isMemoryType(String type) {
        return type != null && List.of("BACKGROUND", "PREFERENCE", "CONSTRAINT", "GOAL", "FACT")
                .contains(type.toUpperCase(Locale.ROOT));
    }

    private boolean isImportance(String importance) {
        return importance != null && List.of("high", "medium", "low")
                .contains(importance.toLowerCase(Locale.ROOT));
    }

    private boolean isTemporaryInstruction(String evidence) {
        return containsAny(evidence, "这次", "这一次", "就这次", "本次", "这轮", "本轮", "这回", "仅这回",
                "临时", "仅此一次", "今天先", "this time", "for this request", "for one response", "temporary", "just this once");
    }

    private String getTextField(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isTextual()) {
            return null;
        }
        return field.asText().trim();
    }

    static String normalizeSegment(String segment) {
        return segment == null ? null : segment.trim().replaceAll("\\s+", " ");
    }

    static boolean containsAny(String value, String... terms) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String normalizeImportance(String importance) {
        if (!StringUtils.hasText(importance)) {
            return "medium";
        }
        String normalized = importance.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "high", "medium", "low" -> normalized;
            default -> "medium";
        };
    }

    record ExtractedMemory(
            String scope,
            String memoryType,
            String content,
            String importance,
            String confidence,
            boolean shouldPersist,
            String evidenceMessageId,
            String evidenceText
    ) {
    }

}
