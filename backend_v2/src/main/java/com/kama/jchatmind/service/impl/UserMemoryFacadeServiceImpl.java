package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.model.response.GetUserMemoriesResponse;
import com.kama.jchatmind.model.response.GetUserMemoryCandidatesResponse;
import com.kama.jchatmind.model.vo.UserMemoryCandidateVO;
import com.kama.jchatmind.model.vo.UserMemoryVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserMemoryFacadeServiceImpl implements UserMemoryFacadeService {

    private static final String MEMORY_EXTRACTION_SYSTEM_PROMPT = """
            你负责从对话中提取值得长期保存的用户信息。
            对每条提取的信息，输出包含以下字段的 JSON 对象：
            - type: 类型，取值为 BACKGROUND / PREFERENCE / CONSTRAINT / GOAL / FACT
            - content: 简洁的陈述句，不超过 120 字
            - importance: 重要程度，取值为 high / medium / low
            - evidence_message_index: 来源消息在输入中的序号（从 0 开始）

            规则：
            - 只提取稳定、可复用的信息
            - 不要保存临时问题、一次性任务、敏感信息
            - 不要提取可以从对话中直接推断出的通用信息
            - 如果与已有记忆存在冲突（更新旧信息或直接矛盾），在 content 中用"更新："前缀标注
            - 如果没有值得提取的信息，返回空数组 []

            只输出 JSON 数组，不要包裹在 markdown 代码块或解释文本中。
            """;

    private final UserMemoryMapper userMemoryMapper;
    private final UserMemoryCandidateMapper userMemoryCandidateMapper;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ObjectMapper objectMapper;
    private final ChatClient memoryExtractionChatClient;
    private final RagService ragService;

    public UserMemoryFacadeServiceImpl(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper userMemoryCandidateMapper,
            ChatMessageFacadeService chatMessageFacadeService
    ) {
        this(userMemoryMapper, userMemoryCandidateMapper, chatMessageFacadeService, null, null);
    }

    @Autowired
    public UserMemoryFacadeServiceImpl(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper userMemoryCandidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            ObjectProvider<ChatClientRegistry> chatClientRegistryProvider,
            ObjectProvider<RagService> ragServiceProvider
    ) {
        this.userMemoryMapper = userMemoryMapper;
        this.userMemoryCandidateMapper = userMemoryCandidateMapper;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.objectMapper = new ObjectMapper();
        this.memoryExtractionChatClient = resolveChatClient(chatClientRegistryProvider);
        this.ragService = ragServiceProvider != null ? ragServiceProvider.getIfAvailable() : null;
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
            for (ChatClient c : registry.getAllClients()) {
                if (c != null) return c;
            }
        }
        return client;
    }

    @Override
    public GetUserMemoriesResponse getUserMemories(String userId) {
        List<UserMemoryVO> result = getConfirmedMemories(requireUserId(userId))
                .stream()
                .map(this::toMemoryVO)
                .toList();
        return GetUserMemoriesResponse.builder()
                .memories(result.toArray(new UserMemoryVO[0]))
                .build();
    }

    @Override
    public GetUserMemoryCandidatesResponse getUserMemoryCandidates(String userId) {
        List<UserMemoryCandidateVO> result = userMemoryCandidateMapper.selectByUserId(requireUserId(userId))
                .stream()
                .map(this::toCandidateVO)
                .toList();
        return GetUserMemoryCandidatesResponse.builder()
                .candidates(result.toArray(new UserMemoryCandidateVO[0]))
                .build();
    }

    @Override
    public void confirmCandidate(String userId, String candidateId) {
        String validatedUserId = requireUserId(userId);
        UserMemoryCandidate candidate = userMemoryCandidateMapper.selectByIdAndUserId(candidateId, validatedUserId);
        if (candidate == null) {
            throw new BizException("候选记忆不存在: " + candidateId);
        }

        String effectiveContent = candidate.getContent();
        boolean isUpdate = effectiveContent.startsWith("更新：");
        if (isUpdate) {
            effectiveContent = effectiveContent.substring(3).trim();
        }

        if (isUpdate) {
            handleConflictUpdate(validatedUserId, candidate, effectiveContent);
        } else if (userMemoryMapper.selectByUserIdAndContent(validatedUserId, effectiveContent) == null) {
            LocalDateTime now = LocalDateTime.now();
            float[] embedding = generateEmbedding(effectiveContent);
            UserMemory userMemory = UserMemory.builder()
                    .userId(validatedUserId)
                    .sessionId(candidate.getSessionId())
                    .memoryType(candidate.getMemoryType())
                    .content(effectiveContent)
                    .importance(candidate.getImportance())
                    .evidenceMessageId(candidate.getEvidenceMessageId())
                    .evidenceText(candidate.getEvidence())
                    .embedding(embedding)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            int result = userMemoryMapper.insert(userMemory);
            if (result <= 0) {
                throw new BizException("确认候选记忆失败");
            }
        }

        userMemoryCandidateMapper.deleteById(candidateId);
    }

    private void handleConflictUpdate(String userId, UserMemoryCandidate candidate, String newContent) {
        List<UserMemory> existingMemories = userMemoryMapper.selectByUserId(userId);
        UserMemory match = existingMemories.stream()
                .filter(m -> m.getMemoryType().equals(candidate.getMemoryType()))
                .findFirst()
                .orElse(null);

        LocalDateTime now = LocalDateTime.now();
        float[] embedding = generateEmbedding(newContent);

        if (match != null) {
            userMemoryMapper.deleteById(match.getId());
        }

        UserMemory userMemory = UserMemory.builder()
                .userId(userId)
                .sessionId(candidate.getSessionId())
                .memoryType(candidate.getMemoryType())
                .content(newContent)
                .importance(candidate.getImportance())
                .evidenceMessageId(candidate.getEvidenceMessageId())
                .evidenceText(candidate.getEvidence())
                .embedding(embedding)
                .createdAt(now)
                .updatedAt(now)
                .build();
        int result = userMemoryMapper.insert(userMemory);
        if (result <= 0) {
            throw new BizException("确认候选记忆失败");
        }
    }

    private float[] generateEmbedding(String text) {
        if (ragService == null || !StringUtils.hasText(text)) {
            return null;
        }
        try {
            return ragService.embed(text);
        } catch (Exception e) {
            log.warn("Failed to generate embedding for memory: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void deleteMemory(String userId, String memoryId) {
        String validatedUserId = requireUserId(userId);
        UserMemory memory = userMemoryMapper.selectByIdAndUserId(memoryId, validatedUserId);
        if (memory == null) {
            throw new BizException("用户记忆不存在: " + memoryId);
        }
        int result = userMemoryMapper.deleteById(memoryId);
        if (result <= 0) {
            throw new BizException("删除用户记忆失败");
        }
    }

    @Override
    public List<UserMemory> getConfirmedMemories(String userId) {
        return userMemoryMapper.selectByUserId(requireUserId(userId));
    }

    @Override
    public List<UserMemory> recallRelevantMemories(String userId, String query, int topK) {
        String validatedUserId = requireUserId(userId);
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        if (ragService == null) {
            log.debug("RagService not available, falling back to recent memories");
            return getConfirmedMemories(validatedUserId).stream()
                    .limit(topK)
                    .toList();
        }

        try {
            float[] queryEmbedding = ragService.embed(query);
            String vectorLiteral = toPgVector(queryEmbedding);
            List<UserMemory> semanticResults = userMemoryMapper.similaritySearch(
                    validatedUserId, vectorLiteral, topK
            );

            if (semanticResults.size() >= topK) {
                return semanticResults;
            }

            List<UserMemory> fallback = getConfirmedMemories(validatedUserId).stream()
                    .filter(m -> m.getEmbedding() == null)
                    .limit(topK - semanticResults.size())
                    .toList();

            List<UserMemory> combined = new ArrayList<>(semanticResults);
            combined.addAll(fallback);
            return combined;
        } catch (Exception e) {
            log.warn("Semantic memory recall failed, falling back to recent memories", e);
            return getConfirmedMemories(validatedUserId).stream()
                    .limit(topK)
                    .toList();
        }
    }

    private String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public void extractMemoryCandidates(String userId, String sessionId) {
        String validatedUserId = requireUserId(userId);
        if (!StringUtils.hasText(sessionId)) {
            return;
        }

        List<ChatMessageDTO> recentMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(
                validatedUserId, sessionId, 8
        );
        List<ChatMessageDTO> userMessages = recentMessages.stream()
                .filter(msg -> msg.getRole() == ChatMessageDTO.RoleType.USER)
                .toList();
        if (userMessages.isEmpty()) {
            return;
        }

        List<ExtractedMemory> extracted;
        if (memoryExtractionChatClient != null) {
            extracted = extractWithLlm(validatedUserId, sessionId, userMessages);
        } else {
            log.warn("No ChatClient available for memory extraction, using keyword-based fallback");
            extracted = extractWithKeywords(userMessages);
        }

        for (ExtractedMemory memory : extracted) {
            persistCandidateIfAbsent(validatedUserId, sessionId, memory);
        }
    }

    private List<ExtractedMemory> extractWithLlm(
            String userId, String sessionId, List<ChatMessageDTO> userMessages
    ) {
        try {
            String existingMemoriesText = formatExistingMemories(userId);
            String messagesText = formatMessagesForExtraction(userMessages);
            String userPrompt = buildExtractionUserPrompt(existingMemoriesText, messagesText);

            String response = memoryExtractionChatClient.prompt()
                    .system(MEMORY_EXTRACTION_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            if (!StringUtils.hasText(response)) {
                return List.of();
            }
            return parseExtractionResponse(response, userMessages);
        } catch (Exception e) {
            log.warn("LLM memory extraction failed, falling back to keyword-based", e);
            return extractWithKeywords(userMessages);
        }
    }

    private String formatExistingMemories(String userId) {
        List<UserMemory> memories = userMemoryMapper.selectByUserId(userId);
        if (memories.isEmpty()) {
            return "无";
        }
        return memories.stream()
                .map(m -> "- [" + m.getMemoryType() + "] " + m.getContent())
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
        return "已有记忆（检查冲突用，不要重复提取已有信息）：\n" + existingMemories + "\n\n"
                + "对话消息（每行格式：[序号] 消息内容）：\n" + messagesText + "\n"
                + "请从以上消息中提取值得长期保存的用户信息。";
    }

    private List<ExtractedMemory> parseExtractionResponse(String response, List<ChatMessageDTO> userMessages) {
        String json = response.trim();
        json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();

        if (!json.startsWith("[")) {
            log.warn("LLM extraction response is not a JSON array: {}", response.substring(0, Math.min(200, response.length())));
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }

            List<ExtractedMemory> result = new ArrayList<>();
            for (JsonNode node : root) {
                String type = getTextField(node, "type");
                String content = getTextField(node, "content");
                String importance = getTextField(node, "importance");
                int evidenceIndex = node.has("evidence_message_index")
                        ? node.get("evidence_message_index").asInt(-1)
                        : -1;

                if (!StringUtils.hasText(type) || !StringUtils.hasText(content)) {
                    continue;
                }
                if (content.length() > 300) {
                    content = content.substring(0, 300);
                }

                String evidenceText = content;
                String evidenceMessageId = null;
                if (evidenceIndex >= 0 && evidenceIndex < userMessages.size()) {
                    ChatMessageDTO sourceMsg = userMessages.get(evidenceIndex);
                    evidenceMessageId = sourceMsg.getId();
                    evidenceText = sourceMsg.getContent();
                    if (evidenceText != null && evidenceText.length() > 500) {
                        evidenceText = evidenceText.substring(0, 500);
                    }
                }

                result.add(new ExtractedMemory(
                        type.toUpperCase(Locale.ROOT),
                        content,
                        importance != null ? importance : "medium",
                        evidenceMessageId,
                        evidenceText != null ? evidenceText : content
                ));
            }
            return result;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM extraction response: {}", e.getMessage());
            return List.of();
        }
    }

    private String getTextField(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isTextual()) {
            return null;
        }
        return field.asText().trim();
    }

    private List<ExtractedMemory> extractWithKeywords(List<ChatMessageDTO> userMessages) {
        List<ExtractedMemory> result = new ArrayList<>();
        for (ChatMessageDTO chatMessage : userMessages) {
            for (ExtractedMemory extractedMemory : extractFromText(chatMessage)) {
                result.add(extractedMemory);
            }
        }
        return result;
    }

    private List<ExtractedMemory> extractFromText(ChatMessageDTO chatMessage) {
        List<ExtractedMemory> result = new ArrayList<>();
        String content = chatMessage.getContent();
        if (!StringUtils.hasText(content)) {
            return result;
        }

        String[] segments = content.replace('\r', '\n').split("[\\n。！？；;]");
        for (String rawSegment : segments) {
            String segment = normalizeSegment(rawSegment);
            if (!StringUtils.hasText(segment) || segment.length() < 4 || segment.length() > 120) {
                continue;
            }
            String memoryType = detectMemoryType(segment);
            if (memoryType == null) {
                continue;
            }
            if (segment.contains("?") || segment.contains("？")) {
                continue;
            }
            result.add(new ExtractedMemory(memoryType, segment, "medium", chatMessage.getId(), segment));
        }
        return result;
    }

    private String detectMemoryType(String segment) {
        if (containsAny(segment, "记住", "以后", "请始终", "请用", "不要", "希望你")) {
            return "CONSTRAINT";
        }
        if (containsAny(segment, "学习目标", "目标", "想学", "正在学", "计划学", "学习")) {
            return "LEARNING_GOAL";
        }
        if (containsAny(segment, "喜欢", "不喜欢", "偏好", "习惯")) {
            return "PREFERENCE";
        }
        if (containsAny(segment, "我是", "我在", "我做", "来自", "背景", "职业")) {
            return "BACKGROUND";
        }
        return null;
    }

    private boolean containsAny(String segment, String... keywords) {
        String normalized = segment.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSegment(String segment) {
        return segment == null ? null : segment.trim().replaceAll("\\s+", " ");
    }

    private void persistCandidateIfAbsent(String userId, String sessionId, ExtractedMemory extractedMemory) {
        if (userMemoryMapper.selectByUserIdAndContent(userId, extractedMemory.content()) != null) {
            return;
        }
        if (userMemoryCandidateMapper.selectByUserIdAndContent(userId, extractedMemory.content()) != null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        UserMemoryCandidate candidate = UserMemoryCandidate.builder()
                .userId(userId)
                .sessionId(sessionId)
                .memoryType(extractedMemory.memoryType())
                .content(extractedMemory.content())
                .evidence(extractedMemory.evidenceText())
                .importance(extractedMemory.importance())
                .evidenceMessageId(extractedMemory.evidenceMessageId())
                .createdAt(now)
                .updatedAt(now)
                .build();
        userMemoryCandidateMapper.insert(candidate);
    }

    private UserMemoryVO toMemoryVO(UserMemory memory) {
        return UserMemoryVO.builder()
                .id(memory.getId())
                .userId(memory.getUserId())
                .sessionId(memory.getSessionId())
                .memoryType(memory.getMemoryType())
                .content(memory.getContent())
                .importance(memory.getImportance())
                .evidenceMessageId(memory.getEvidenceMessageId())
                .evidenceText(memory.getEvidenceText())
                .build();
    }

    private UserMemoryCandidateVO toCandidateVO(UserMemoryCandidate candidate) {
        return UserMemoryCandidateVO.builder()
                .id(candidate.getId())
                .userId(candidate.getUserId())
                .sessionId(candidate.getSessionId())
                .memoryType(candidate.getMemoryType())
                .content(candidate.getContent())
                .evidence(candidate.getEvidence())
                .importance(candidate.getImportance())
                .evidenceMessageId(candidate.getEvidenceMessageId())
                .build();
    }

    private String requireUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BizException("userId 不能为空");
        }
        return userId.trim();
    }

    private record ExtractedMemory(
            String memoryType,
            String content,
            String importance,
            String evidenceMessageId,
            String evidenceText
    ) {
    }
}
