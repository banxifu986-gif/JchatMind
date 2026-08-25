package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.event.ChatSessionDeletedEvent;
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
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserMemoryFacadeServiceImpl implements UserMemoryFacadeService {

    private static final String MEMORY_UPDATE_PREFIX = "更新：";
    private static final int RECENT_MESSAGES_FOR_EXTRACTION = 8;
    private static final int MIN_NEW_USER_MESSAGES_FOR_EXTRACTION = 3;
    private static final long DEFAULT_MEMORY_EXPIRATION_DAYS = 365L;
    private static final double SEMANTIC_DUPLICATE_MAX_COSINE_DISTANCE = 0.05D;
    private static final String MEMORY_EXTRACTION_SYSTEM_PROMPT = """
            你负责从对话中提取值得长期保存的用户信息。
            对每条提取的信息，输出包含以下字段的 JSON 对象：
            - type: 类型，取值为 BACKGROUND / PREFERENCE / CONSTRAINT / GOAL / FACT
            - content: 简洁的陈述句，不超过 120 字
            - importance: 重要程度，取值为 high / medium / low
            - should_persist: 是否应该写入长期记忆，取值 true / false
            - evidence_message_index: 来源消息在输入中的序号，从 0 开始

            规则：
            - 只提取稳定、可复用、对未来回答有帮助的信息
            - 不要保存一次性任务、短期上下文、敏感信息、纯闲聊信息
            - 不要提取可以从对话中直接推断出的通用信息
            - 如果与已有记忆存在冲突，content 使用"更新："前缀
            - 如果没有值得提取的信息，返回空数组 []

            只输出 JSON 数组，不要输出 markdown 代码块或解释文字。
            """;

    private final UserMemoryMapper userMemoryMapper;
    private final UserMemoryCandidateMapper userMemoryCandidateMapper;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ObjectMapper objectMapper;
    private final ChatClient memoryExtractionChatClient;
    private final RagService ragService;
    private final RequestScopeData requestScopeData;
    private final ConcurrentHashMap<String, ExtractionState> extractionStates = new ConcurrentHashMap<>();

    public UserMemoryFacadeServiceImpl(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper userMemoryCandidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            RequestScopeData requestScopeData
    ) {
        this(userMemoryMapper, userMemoryCandidateMapper, chatMessageFacadeService, requestScopeData, null, null);
    }

    @Autowired
    public UserMemoryFacadeServiceImpl(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper userMemoryCandidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            RequestScopeData requestScopeData,
            ObjectProvider<ChatClientRegistry> chatClientRegistryProvider,
            ObjectProvider<RagService> ragServiceProvider
    ) {
        this.userMemoryMapper = userMemoryMapper;
        this.userMemoryCandidateMapper = userMemoryCandidateMapper;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.requestScopeData = requestScopeData;
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
            for (ChatClient fallback : registry.getAllClients()) {
                if (fallback != null) {
                    return fallback;
                }
            }
        }
        return client;
    }

    @Override
    public GetUserMemoriesResponse getUserMemories() {
        String userId = requireUserId();
        List<UserMemoryVO> result = userMemoryMapper.selectByUserId(userId)
                .stream()
                .map(this::toMemoryVO)
                .toList();
        return GetUserMemoriesResponse.builder()
                .memories(result.toArray(new UserMemoryVO[0]))
                .build();
    }

    @Override
    public GetUserMemoryCandidatesResponse getUserMemoryCandidates() {
        String userId = requireUserId();
        List<UserMemoryCandidateVO> result = getMemoryCandidatesInternal(userId)
                .stream()
                .map(this::toCandidateVO)
                .toList();
        return GetUserMemoryCandidatesResponse.builder()
                .candidates(result.toArray(new UserMemoryCandidateVO[0]))
                .build();
    }

    @Override
    @Transactional
    public void confirmUserMemoryCandidate(String candidateId) {
        String userId = requireUserId();
        UserMemoryCandidate candidate = userMemoryCandidateMapper.selectByIdAndUserId(candidateId, userId);
        if (candidate == null) {
            throw new BizException("候选记忆不存在: " + candidateId);
        }
        if (!UserMemoryCandidate.STATUS_PENDING.equals(candidate.getStatus())) {
            throw new BizException("候选记忆状态不可确认");
        }
        if (userMemoryCandidateMapper.markPersistedIfPending(candidateId, userId) <= 0) {
            throw new BizException("候选记忆状态不可确认");
        }

        String originalContent = normalizeSegment(candidate.getContent());
        if (!StringUtils.hasText(originalContent)) {
            throw new BizException("候选记忆内容为空");
        }
        boolean isUpdate = originalContent.startsWith(MEMORY_UPDATE_PREFIX);
        String effectiveContent = isUpdate
                ? originalContent.substring(MEMORY_UPDATE_PREFIX.length()).trim()
                : originalContent;
        if (!StringUtils.hasText(effectiveContent)) {
            throw new BizException("候选记忆内容为空");
        }
        if (findConfirmedMemoryByContent(userId, effectiveContent) != null) {
            return;
        }
        if (isUpdate) {
            handleConflictUpdate(userId, candidate, effectiveContent);
        } else {
            float[] contentEmbedding = generateEmbedding(effectiveContent);
            if (hasSemanticDuplicate(userId, candidate.getMemoryType(), contentEmbedding)) {
                return;
            }
            insertConfirmedMemory(userId, candidate, effectiveContent, contentEmbedding);
        }
    }

    @Override
    public void discardUserMemoryCandidate(String candidateId) {
        String userId = requireUserId();
        UserMemoryCandidate candidate = userMemoryCandidateMapper.selectByIdAndUserId(candidateId, userId);
        if (candidate == null) {
            throw new BizException("候选记忆不存在: " + candidateId);
        }
        if (!UserMemoryCandidate.STATUS_PENDING.equals(candidate.getStatus())) {
            throw new BizException("候选记忆状态不可忽略");
        }
        if (userMemoryCandidateMapper.markDiscardedIfPending(candidateId, userId) <= 0) {
            throw new BizException("候选记忆状态不可忽略");
        }
    }

    @Override
    public void deleteMemory(String memoryId) {
        String userId = requireUserId();
        UserMemory memory = getConfirmedMemoryById(userId, memoryId);
        if (memory == null) {
            throw new BizException("用户记忆不存在: " + memoryId);
        }
        int result = userMemoryMapper.deleteById(memoryId);
        if (result <= 0) {
            throw new BizException("删除用户记忆失败");
        }
    }

    @Override
    public void updateMemory(String memoryId, String content) {
        String normalizedContent = normalizeSegment(content);
        if (!StringUtils.hasText(normalizedContent)) {
            throw new BizException("用户记忆内容不能为空");
        }
        String userId = requireUserId();
        UserMemory memory = getConfirmedMemoryById(userId, memoryId);
        if (memory == null) {
            throw new BizException("用户记忆不存在: " + memoryId);
        }
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(DEFAULT_MEMORY_EXPIRATION_DAYS);
        int result = userMemoryMapper.updateContentEmbeddingAndExpiration(
                memoryId,
                userId,
                normalizedContent,
                generateEmbedding(normalizedContent),
                expiresAt
        );
        if (result <= 0) {
            throw new BizException("更新用户记忆失败");
        }
    }

    @Override
    public void updateMemoryExpiration(String memoryId, LocalDateTime expiresAt) {
        String userId = requireUserId();
        UserMemory memory = getConfirmedMemoryById(userId, memoryId);
        if (memory == null) {
            throw new BizException("用户记忆不存在: " + memoryId);
        }
        if (expiresAt == null) {
            throw new BizException("过期时间不能为空");
        }
        if (!expiresAt.isAfter(LocalDateTime.now())) {
            throw new BizException("过期时间必须晚于当前时间");
        }
        if (userMemoryMapper.updateExpiration(memoryId, userId, expiresAt) <= 0) {
            throw new BizException("更新用户记忆过期时间失败");
        }
    }

    @Override
    public void clearUserMemories() {
        userMemoryMapper.deleteByUserId(requireUserId());
    }

    @Override
    public List<UserMemory> getConfirmedMemories(String userId) {
        return userMemoryMapper.selectActiveByUserId(userId);
    }

    @Override
    public List<UserMemory> recallRelevantMemories(String userId, String query, int topK) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        if (ragService == null) {
            log.debug("RagService not available, falling back to recent memories");
            try {
                return getConfirmedMemoriesInternal(userId).stream()
                        .limit(topK)
                        .toList();
            } catch (Exception e) {
                log.warn("Failed to load confirmed memories as fallback, returning empty", e);
                return List.of();
            }
        }

        try {
            float[] queryEmbedding = ragService.embed(query);
            String vectorLiteral = toPgVector(queryEmbedding);
            List<UserMemory> semanticResults = similaritySearchMemories(userId, vectorLiteral, topK);
            if (semanticResults.size() >= topK) {
                return semanticResults;
            }

            List<UserMemory> fallback;
            try {
                fallback = getConfirmedMemoriesInternal(userId).stream()
                        .filter(memory -> memory.getEmbedding() == null)
                        .limit(topK - semanticResults.size())
                        .toList();
            } catch (Exception e) {
                log.warn("Failed to load non-embedding memories as fallback, returning semantic results only", e);
                return semanticResults;
            }

            List<UserMemory> combined = new ArrayList<>(semanticResults);
            combined.addAll(fallback);
            return combined;
        } catch (Exception e) {
            log.warn("Semantic memory recall failed, falling back to recent memories", e);
            try {
                return getConfirmedMemoriesInternal(userId).stream()
                        .limit(topK)
                        .toList();
            } catch (Exception fallbackException) {
                log.warn("All memory recall paths failed, returning empty", fallbackException);
                return List.of();
            }
        }
    }

    @Override
    public MemoryExtractionResult extractMemoryCandidates(String userId, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return MemoryExtractionResult.SKIPPED;
        }

        ExtractionState state = extractionStates.computeIfAbsent(sessionId, ignored -> new ExtractionState());
        synchronized (state) {
            int userMessageCount;
            try {
                userMessageCount = chatMessageFacadeService.countUserMessagesBySessionId(sessionId, userId);
            } catch (RuntimeException e) {
                extractionStates.remove(sessionId, state);
                throw e;
            }
            if (!state.shouldExtract(userMessageCount)) {
                return MemoryExtractionResult.SKIPPED;
            }

            List<ChatMessageDTO> recentMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(
                    sessionId,
                    RECENT_MESSAGES_FOR_EXTRACTION,
                    userId
            );
            List<ChatMessageDTO> userMessages = recentMessages.stream()
                    .filter(msg -> msg.getRole() == ChatMessageDTO.RoleType.USER)
                    .toList();
            if (userMessages.isEmpty()) {
                return MemoryExtractionResult.SKIPPED;
            }

            List<ExtractedMemory> extracted = memoryExtractionChatClient != null
                    ? extractWithLlm(userId, userMessages)
                    : extractWithKeywords(userMessages);
            if (memoryExtractionChatClient == null) {
                log.warn("No ChatClient available for memory extraction, using keyword-based fallback");
            }

            for (ExtractedMemory memory : extracted) {
                persistAutomatically(userId, sessionId, memory);
            }
            state.markExtracted(userMessageCount);
            return MemoryExtractionResult.EXTRACTED;
        }
    }

    @EventListener
    public void onChatSessionDeleted(ChatSessionDeletedEvent event) {
        extractionStates.remove(event.sessionId());
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

    private List<ExtractedMemory> extractWithLlm(String userId, List<ChatMessageDTO> userMessages) {
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
                throw new IllegalStateException("LLM memory extraction response is blank");
            }
            return parseExtractionResponse(response, userMessages);
        } catch (Exception e) {
            log.warn("LLM memory extraction failed, falling back to keyword-based: {}", e.getClass().getName());
            return extractWithKeywords(userMessages);
        }
    }

    private String formatExistingMemories(String userId) {
        List<UserMemory> memories = getConfirmedMemoriesInternal(userId);
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
                String type = getTextField(node, "type");
                String content = getTextField(node, "content");
                String importance = getTextField(node, "importance");
                boolean shouldPersist = node.has("should_persist") && node.get("should_persist").asBoolean(false);
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
                        normalizeImportance(importance),
                        shouldPersist,
                        evidenceMessageId,
                        evidenceText != null ? evidenceText : content
                ));
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LLM memory extraction response is invalid", e);
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
            result.addAll(extractFromText(chatMessage));
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
            if (memoryType == null || segment.contains("?") || segment.contains("？")) {
                continue;
            }
            result.add(new ExtractedMemory(memoryType, segment, "medium", true, chatMessage.getId(), segment));
        }
        return result;
    }

    private String detectMemoryType(String segment) {
        if (containsAny(segment, "记住", "以后", "请始终", "请用", "不要", "希望你")) {
            return "CONSTRAINT";
        }
        if (containsAny(segment, "学习目标", "目标", "想学", "正在学", "计划学", "学习")) {
            return "GOAL";
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

    private void persistAutomatically(String userId, String sessionId, ExtractedMemory extractedMemory) {
        String originalContent = normalizeSegment(extractedMemory.content());
        if (!StringUtils.hasText(originalContent)) {
            return;
        }

        String effectiveContent = originalContent;
        boolean isUpdate = effectiveContent.startsWith(MEMORY_UPDATE_PREFIX);
        if (isUpdate) {
            effectiveContent = effectiveContent.substring(MEMORY_UPDATE_PREFIX.length()).trim();
        }
        if (!StringUtils.hasText(effectiveContent)) {
            return;
        }
        if (findConfirmedMemoryByContent(userId, effectiveContent) != null) {
            return;
        }
        if (findMemoryCandidateByContent(userId, originalContent) != null) {
            return;
        }

        UserMemoryCandidate candidate = insertCandidate(userId, sessionId, extractedMemory, originalContent);
        if (!shouldPersist(extractedMemory)) {
            updateCandidateStatus(candidate.getId(), UserMemoryCandidate.STATUS_DISCARDED);
        }
    }

    private UserMemoryCandidate insertCandidate(
            String userId,
            String sessionId,
            ExtractedMemory extractedMemory,
            String content
    ) {
        LocalDateTime now = LocalDateTime.now();
        UserMemoryCandidate candidate = UserMemoryCandidate.builder()
                .userId(userId)
                .sessionId(sessionId)
                .memoryType(extractedMemory.memoryType())
                .content(content)
                .evidence(extractedMemory.evidenceText())
                .importance(extractedMemory.importance())
                .evidenceMessageId(extractedMemory.evidenceMessageId())
                .status(UserMemoryCandidate.STATUS_PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        int result = userMemoryCandidateMapper.insert(candidate);
        if (result <= 0) {
            throw new BizException("写入候选记忆失败");
        }
        return candidate;
    }

    private boolean shouldPersist(ExtractedMemory extractedMemory) {
        return extractedMemory.shouldPersist() && isImportancePersistable(extractedMemory.importance());
    }

    private boolean isImportancePersistable(String importance) {
        String normalized = normalizeImportance(importance);
        return "high".equals(normalized) || "medium".equals(normalized);
    }

    private String normalizeImportance(String importance) {
        if (!StringUtils.hasText(importance)) {
            return "medium";
        }
        String normalized = importance.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "high", "medium", "low" -> normalized;
            default -> "medium";
        };
    }

    private void insertConfirmedMemory(
            String userId,
            UserMemoryCandidate candidate,
            String content,
            float[] embedding
    ) {
        if (findConfirmedMemoryByContent(userId, content) != null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        UserMemory userMemory = UserMemory.builder()
                .userId(userId)
                .sessionId(candidate.getSessionId())
                .memoryType(candidate.getMemoryType())
                .content(content)
                .importance(candidate.getImportance())
                .evidenceMessageId(candidate.getEvidenceMessageId())
                .evidenceText(candidate.getEvidence())
                .expiresAt(now.plusDays(DEFAULT_MEMORY_EXPIRATION_DAYS))
                .embedding(embedding)
                .createdAt(now)
                .updatedAt(now)
                .build();
        int result = userMemoryMapper.insert(userMemory);
        if (result <= 0) {
            throw new BizException("写入用户长期记忆失败");
        }
    }

    private void handleConflictUpdate(String userId, UserMemoryCandidate candidate, String newContent) {
        List<UserMemory> existingMemories = getConfirmedMemoriesInternal(userId);
        UserMemory match = existingMemories.stream()
                .filter(memory -> memory.getMemoryType().equals(candidate.getMemoryType()))
                .findFirst()
                .orElse(null);

        LocalDateTime now = LocalDateTime.now();
        float[] embedding = generateEmbedding(newContent);
        UserMemory userMemory = UserMemory.builder()
                .userId(userId)
                .sessionId(candidate.getSessionId())
                .memoryType(candidate.getMemoryType())
                .content(newContent)
                .importance(candidate.getImportance())
                .evidenceMessageId(candidate.getEvidenceMessageId())
                .evidenceText(candidate.getEvidence())
                .expiresAt(now.plusDays(DEFAULT_MEMORY_EXPIRATION_DAYS))
                .embedding(embedding)
                .createdAt(now)
                .updatedAt(now)
                .build();
        int result = userMemoryMapper.insert(userMemory);
        if (result <= 0) {
            throw new BizException("写入冲突更新记忆失败");
        }
        if (match != null && (!StringUtils.hasText(userMemory.getId())
                || userMemoryMapper.markSupersededById(match.getId(), userMemory.getId()) <= 0)) {
            throw new BizException("保存记忆冲突关系失败");
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

    private boolean hasSemanticDuplicate(String userId, String memoryType, float[] contentEmbedding) {
        if (contentEmbedding == null) {
            return false;
        }
        try {
            return getConfirmedMemoriesInternal(userId).stream()
                    .filter(memory -> memoryType != null && memoryType.equals(memory.getMemoryType()))
                    .anyMatch(memory -> cosineDistance(contentEmbedding, memory.getEmbedding())
                            <= SEMANTIC_DUPLICATE_MAX_COSINE_DISTANCE);
        } catch (Exception e) {
            log.warn("Failed to check semantic memory duplicates: {}", e.getMessage());
            return false;
        }
    }

    private double cosineDistance(float[] first, float[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) {
            return Double.POSITIVE_INFINITY;
        }
        double dotProduct = 0D;
        double firstMagnitude = 0D;
        double secondMagnitude = 0D;
        for (int i = 0; i < first.length; i++) {
            double firstValue = first[i];
            double secondValue = second[i];
            if (!Double.isFinite(firstValue) || !Double.isFinite(secondValue)) {
                return Double.POSITIVE_INFINITY;
            }
            dotProduct += firstValue * secondValue;
            firstMagnitude += firstValue * firstValue;
            secondMagnitude += secondValue * secondValue;
        }
        if (!Double.isFinite(dotProduct)
                || !Double.isFinite(firstMagnitude)
                || !Double.isFinite(secondMagnitude)
                || firstMagnitude == 0D
                || secondMagnitude == 0D) {
            return Double.POSITIVE_INFINITY;
        }
        double similarity = dotProduct / (Math.sqrt(firstMagnitude) * Math.sqrt(secondMagnitude));
        if (!Double.isFinite(similarity)) {
            return Double.POSITIVE_INFINITY;
        }
        return 1D - Math.max(-1D, Math.min(1D, similarity));
    }

    private void updateCandidateStatus(String candidateId, String status) {
        int result = userMemoryCandidateMapper.updateStatusById(candidateId, status);
        if (result <= 0) {
            throw new BizException("更新候选记忆状态失败");
        }
    }

    private List<UserMemoryCandidate> getMemoryCandidatesInternal(String userId) {
        List<UserMemoryCandidate> candidates = userMemoryCandidateMapper.selectByUserId(userId);
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> UserMemoryCandidate.STATUS_PENDING.equals(candidate.getStatus()))
                .toList();
    }

    private UserMemory getConfirmedMemoryById(String userId, String memoryId) {
        return userMemoryMapper.selectByIdAndUserId(memoryId, userId);
    }

    private List<UserMemory> similaritySearchMemories(String userId, String vectorLiteral, int topK) {
        return userMemoryMapper.similaritySearch(userId, vectorLiteral, topK);
    }

    private UserMemory findConfirmedMemoryByContent(String userId, String content) {
        return userMemoryMapper.selectByUserIdAndContent(userId, content);
    }

    private UserMemoryCandidate findMemoryCandidateByContent(String userId, String content) {
        return userMemoryCandidateMapper.selectByUserIdAndContent(userId, content);
    }

    private List<UserMemory> getConfirmedMemoriesInternal(String userId) {
        return userMemoryMapper.selectActiveByUserId(userId);
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
                .expiresAt(memory.getExpiresAt())
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
                .status(candidate.getStatus())
                .build();
    }

    private String requireUserId() {
        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            throw new BizException("用户未登录");
        }
        return String.valueOf(userId);
    }

    private record ExtractedMemory(
            String memoryType,
            String content,
            String importance,
            boolean shouldPersist,
            String evidenceMessageId,
            String evidenceText
    ) {
    }

    private static class ExtractionState {
        private int lastExtractedUserMessageCount;

        private boolean shouldExtract(int userMessageCount) {
            return lastExtractedUserMessageCount == 0
                    || userMessageCount < lastExtractedUserMessageCount
                    || userMessageCount - lastExtractedUserMessageCount >= MIN_NEW_USER_MESSAGES_FOR_EXTRACTION;
        }

        private void markExtracted(int userMessageCount) {
            lastExtractedUserMessageCount = userMessageCount;
        }
    }
}
