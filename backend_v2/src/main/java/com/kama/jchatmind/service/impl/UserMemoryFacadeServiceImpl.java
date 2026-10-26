package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.event.ChatSessionDeletedEvent;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryConsolidationProposalMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.model.entity.UserMemoryConsolidationProposal;
import com.kama.jchatmind.model.response.GetUserMemoriesResponse;
import com.kama.jchatmind.model.response.GetUserMemoryCandidatesResponse;
import com.kama.jchatmind.model.response.UserMemoryAutomationResponse;
import com.kama.jchatmind.model.response.GetUserMemoryConsolidationProposalsResponse;
import com.kama.jchatmind.model.vo.UserMemoryCandidateVO;
import com.kama.jchatmind.model.vo.UserMemoryVO;
import com.kama.jchatmind.model.vo.UserMemoryConsolidationProposalVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import com.kama.jchatmind.service.impl.UserMemoryModelClient.ExtractedMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import static com.kama.jchatmind.service.impl.UserMemoryWriteService.DEFAULT_MEMORY_EXPIRATION_DAYS;
import static com.kama.jchatmind.service.impl.UserMemoryWriteService.fingerprintMemories;
import static com.kama.jchatmind.service.impl.UserMemoryWriteService.parseSourceMemoryIds;
import static com.kama.jchatmind.service.impl.UserMemoryModelClient.containsAny;
import static com.kama.jchatmind.service.impl.UserMemoryModelClient.normalizeImportance;
import static com.kama.jchatmind.service.impl.UserMemoryModelClient.normalizeSegment;

@Service
@Slf4j
public class UserMemoryFacadeServiceImpl implements UserMemoryFacadeService {

    private static final String MEMORY_UPDATE_PREFIX = "更新：";
    private static final String AUTOMATION_REVIEW_ALL = "REVIEW_ALL";
    private static final String AUTOMATION_BALANCED = "BALANCED";
    private static final String AUTOMATION_AUTOMATIC = "AUTOMATIC";
    private static final int RECENT_MESSAGES_FOR_EXTRACTION = 8;
    private static final int MIN_NEW_USER_MESSAGES_FOR_EXTRACTION = 3;
    private static final int MAX_RECALL_CONTEXT_CHARACTERS = 20_000;
    private final UserMemoryMapper userMemoryMapper;
    private final UserMemoryWriteService memoryWriter;
    private final UserMemoryCandidateMapper userMemoryCandidateMapper;
    private final UserMemoryConsolidationProposalMapper consolidationProposalMapper;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final UserMemoryModelClient memoryModel;
    private final RagService ragService;
    private final RequestScopeData requestScopeData;
    private final ConcurrentHashMap<String, ExtractionState> extractionStates = new ConcurrentHashMap<>();

    public UserMemoryFacadeServiceImpl(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper userMemoryCandidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            RequestScopeData requestScopeData,
            UserMemoryWriteService memoryWriter
    ) {
        this(userMemoryMapper, userMemoryCandidateMapper, chatMessageFacadeService, requestScopeData, null, null, memoryWriter);
    }

    public UserMemoryFacadeServiceImpl(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper userMemoryCandidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            RequestScopeData requestScopeData,
            ObjectProvider<ChatClientRegistry> chatClientRegistryProvider,
            ObjectProvider<RagService> ragServiceProvider,
            UserMemoryWriteService memoryWriter
    ) {
        this(userMemoryMapper, userMemoryCandidateMapper, chatMessageFacadeService, requestScopeData,
                chatClientRegistryProvider, ragServiceProvider, null, memoryWriter);
    }

    @Autowired
    public UserMemoryFacadeServiceImpl(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper userMemoryCandidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            RequestScopeData requestScopeData,
            ObjectProvider<ChatClientRegistry> chatClientRegistryProvider,
            ObjectProvider<RagService> ragServiceProvider,
            UserMemoryConsolidationProposalMapper consolidationProposalMapper,
            UserMemoryWriteService memoryWriter
    ) {
        this.userMemoryMapper = userMemoryMapper;
        this.memoryWriter = memoryWriter;
        this.userMemoryCandidateMapper = userMemoryCandidateMapper;
        this.consolidationProposalMapper = consolidationProposalMapper;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.requestScopeData = requestScopeData;
        this.memoryModel = new UserMemoryModelClient(chatClientRegistryProvider);
        this.ragService = ragServiceProvider != null ? ragServiceProvider.getIfAvailable() : null;
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
    public UserMemoryAutomationResponse getMemoryAutomationMode() {
        String mode = userMemoryMapper.selectAutomationMode(requireUserId());
        if (!isAutomationMode(mode)) {
            mode = AUTOMATION_BALANCED;
        }
        return UserMemoryAutomationResponse.builder().mode(mode).build();
    }

    @Override
    public void updateMemoryAutomationMode(String mode) {
        String normalizedMode = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (!isAutomationMode(normalizedMode)) {
            throw new BizException("记忆自动化模式无效");
        }
        if (userMemoryMapper.upsertAutomationMode(requireUserId(), normalizedMode) <= 0) {
            throw new BizException("更新记忆自动化模式失败");
        }
    }

    private boolean isAutomationMode(String mode) {
        return AUTOMATION_REVIEW_ALL.equals(mode)
                || AUTOMATION_BALANCED.equals(mode)
                || AUTOMATION_AUTOMATIC.equals(mode);
    }

    @Override
    public void createMemoryConsolidationProposal() {
        String userId = requireUserId();
        List<UserMemory> memories = getConfirmedMemoriesInternal(userId);
        if (memories.size() < 10) {
            throw new BizException("至少需要 10 条已确认记忆才能生成整理建议");
        }
        UserMemoryConsolidationProposal proposal = memoryModel.proposeConsolidation(
                userId, memories.stream().limit(10).toList());
        if (consolidationProposalMapper.insert(proposal) <= 0) {
            throw new BizException("保存记忆整理建议失败");
        }
    }

    @Override
    public GetUserMemoryConsolidationProposalsResponse getMemoryConsolidationProposals() {
        String userId = requireUserId();
        List<UserMemoryConsolidationProposalVO> proposals = consolidationProposalMapper
                .selectPendingByUserId(userId).stream()
                .map(this::toConsolidationProposalVO)
                .toList();
        return GetUserMemoryConsolidationProposalsResponse.builder()
                .proposals(proposals.toArray(new UserMemoryConsolidationProposalVO[0]))
                .build();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void confirmMemoryConsolidationProposal(String proposalId) {
        String userId = requireUserId();
        UserMemoryConsolidationProposal proposal = consolidationProposalMapper
                .selectPendingByIdAndUserId(proposalId, userId);
        if (proposal == null) {
            throw new BizException("待处理的整理建议不存在");
        }
        float[] embedding = generateEmbedding(proposal.getProposedContent());
        memoryWriter.confirmConsolidation(userId, proposal, embedding);
    }

    @Override
    public void discardMemoryConsolidationProposal(String proposalId) {
        String userId = requireUserId();
        if (consolidationProposalMapper.markStatus(proposalId, userId, "DISCARDED", null) <= 0) {
            throw new BizException("待处理的整理建议不存在");
        }
    }

    private UserMemoryConsolidationProposalVO toConsolidationProposalVO(UserMemoryConsolidationProposal proposal) {
        return UserMemoryConsolidationProposalVO.builder()
                .id(proposal.getId())
                .sourceMemoryIds(parseSourceMemoryIds(proposal.getSourceMemoryIdsJson()))
                .proposedType(proposal.getProposedType())
                .proposedContent(proposal.getProposedContent())
                .status(proposal.getStatus())
                .build();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void confirmUserMemoryCandidate(String candidateId) {
        String userId = requireUserId();
        UserMemoryCandidate candidate = userMemoryCandidateMapper.selectByIdAndUserId(candidateId, userId);
        if (candidate == null) {
            throw new BizException("候选记忆不存在: " + candidateId);
        }
        if (!UserMemoryCandidate.STATUS_PENDING.equals(candidate.getStatus())) {
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
        float[] embedding = generateEmbedding(effectiveContent);
        memoryWriter.confirmCandidate(userId, candidate, effectiveContent, isUpdate, embedding);
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
                return fitRecallCharacterBudget(getConfirmedMemoriesInternal(userId).stream()
                        .limit(topK)
                        .toList());
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
                return fitRecallCharacterBudget(semanticResults);
            }

            List<UserMemory> fallback;
            try {
                fallback = getConfirmedMemoriesInternal(userId).stream()
                        .filter(memory -> memory.getEmbedding() == null)
                        .limit(topK - semanticResults.size())
                        .toList();
            } catch (Exception e) {
                log.warn("Failed to load non-embedding memories as fallback, returning semantic results only", e);
                return fitRecallCharacterBudget(semanticResults);
            }

            List<UserMemory> combined = new ArrayList<>(semanticResults);
            combined.addAll(fallback);
            return fitRecallCharacterBudget(combined);
        } catch (Exception e) {
            log.warn("Semantic memory recall failed, falling back to recent memories", e);
            try {
                return fitRecallCharacterBudget(getConfirmedMemoriesInternal(userId).stream()
                        .limit(topK)
                        .toList());
            } catch (Exception fallbackException) {
                log.warn("All memory recall paths failed, returning empty", fallbackException);
                return List.of();
            }
        }
    }

    private List<UserMemory> fitRecallCharacterBudget(List<UserMemory> memories) {
        List<UserMemory> included = new ArrayList<>();
        int usedCharacters = 0;
        for (UserMemory memory : memories) {
            String rendered = "- [" + memory.getMemoryType() + "] " + memory.getContent() + "\n";
            if (usedCharacters + rendered.length() > MAX_RECALL_CONTEXT_CHARACTERS) {
                continue;
            }
            included.add(memory);
            usedCharacters += rendered.length();
        }
        return included;
    }

    @Override
    public MemoryExtractionResult extractMemoryCandidates(String userId, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return MemoryExtractionResult.SKIPPED;
        }

        ExtractionState localState = extractionStates.computeIfAbsent(sessionId, ignored -> new ExtractionState());
        synchronized (localState) {
            int userMessageCount;
            try {
                userMessageCount = chatMessageFacadeService.countUserMessagesBySessionId(sessionId, userId);
            } catch (RuntimeException exception) {
                localState.lastExtractedUserMessageCount = 0;
                extractionStates.remove(sessionId, localState);
                throw exception;
            }
            if (userMessageCount <= 0) {
                return MemoryExtractionResult.SKIPPED;
            }
            int persistedCount = userMemoryMapper.selectLastExtractedUserMessageCount(userId, sessionId);
            int lastExtractedMessageCount = Math.max(persistedCount, localState.lastExtractedUserMessageCount);
            if (!shouldExtract(lastExtractedMessageCount, userMessageCount)) {
                return MemoryExtractionResult.SKIPPED;
            }
            userMemoryMapper.markExtractionAttempt(userId, sessionId);
            try {
                List<ChatMessageDTO> recentMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(
                        sessionId,
                        RECENT_MESSAGES_FOR_EXTRACTION,
                        userId
                );
                List<ChatMessageDTO> userMessages = recentMessages.stream()
                        .filter(msg -> msg.getRole() == ChatMessageDTO.RoleType.USER)
                        .toList();
                if (userMessages.isEmpty()) {
                    if (userMessageCount > 0) {
                        throw new IllegalStateException("No user messages available for memory extraction");
                    }
                    userMemoryMapper.markExtractionSuccess(userId, sessionId, userMessageCount);
                    localState.lastExtractedUserMessageCount = userMessageCount;
                    return MemoryExtractionResult.SKIPPED;
                }

                if (!memoryModel.isAvailable()) {
                    throw new IllegalStateException("No ChatClient available for memory extraction");
                }
                List<ExtractedMemory> extracted = memoryModel.extract(getConfirmedMemoriesInternal(userId), userMessages);

                for (ExtractedMemory memory : extracted) {
                    persistAutomatically(userId, sessionId, memory);
                }
                userMemoryMapper.markExtractionSuccess(userId, sessionId, userMessageCount);
                localState.lastExtractedUserMessageCount = userMessageCount;
                return MemoryExtractionResult.EXTRACTED;
            } catch (RuntimeException e) {
                try {
                    userMemoryMapper.markExtractionFailure(userId, sessionId, e.getClass().getSimpleName());
                } catch (RuntimeException stateFailure) {
                    log.warn("Failed to persist memory extraction failure state: {}", stateFailure.getClass().getSimpleName());
                }
                throw e;
            }
        }
    }

    @EventListener
    public void onChatSessionDeleted(ChatSessionDeletedEvent event) {
        extractionStates.remove(event.sessionId());
    }

    private static class ExtractionState {
        private int lastExtractedUserMessageCount;
    }

    private boolean shouldExtract(int previousCount, int currentCount) {
        return previousCount == 0
                || currentCount < previousCount
                || currentCount - previousCount >= MIN_NEW_USER_MESSAGES_FOR_EXTRACTION;
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

    private void persistAutomatically(String userId, String sessionId, ExtractedMemory extractedMemory) {
        if (!"PERSISTENT".equals(extractedMemory.scope())
                || !extractedMemory.shouldPersist()
                || !isImportancePersistable(extractedMemory.importance())) {
            return;
        }
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

        if (shouldAutomaticallySave(userId, extractedMemory, originalContent)) {
            UserMemoryCandidate automaticMemory = UserMemoryCandidate.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(extractedMemory.memoryType())
                    .content(originalContent)
                    .evidence(extractedMemory.evidenceText())
                    .importance(extractedMemory.importance())
                    .evidenceMessageId(extractedMemory.evidenceMessageId())
                    .build();
            float[] embedding = generateEmbedding(effectiveContent);
            if (memoryWriter.hasSemanticDuplicate(userId, extractedMemory.memoryType(), embedding)) {
                return;
            }
            memoryWriter.insertConfirmedMemory(userId, automaticMemory, effectiveContent, embedding);
            return;
        }
        insertCandidate(userId, sessionId, extractedMemory, originalContent);
    }

    private boolean shouldAutomaticallySave(String userId, ExtractedMemory memory, String content) {
        String mode = userMemoryMapper.selectAutomationMode(userId);
        if (!StringUtils.hasText(mode)) {
            mode = AUTOMATION_BALANCED;
        }
        if (!isAutomationMode(mode)
                || AUTOMATION_REVIEW_ALL.equals(mode)
                || !"high".equals(memory.confidence())
                || content.startsWith(MEMORY_UPDATE_PREFIX)
                || "CONSTRAINT".equals(memory.memoryType())
                || "FACT".equals(memory.memoryType())
                || "GOAL".equals(memory.memoryType())
                || containsSensitiveMemoryContent(content + " " + memory.evidenceText())) {
            return false;
        }
        if (AUTOMATION_BALANCED.equals(mode)) {
            return "PREFERENCE".equals(memory.memoryType()) && isExplicitPreference(memory.evidenceText());
        }
        return AUTOMATION_AUTOMATIC.equals(mode);
    }

    private boolean isExplicitPreference(String evidence) {
        return containsAny(evidence, "偏好", "喜欢", "更喜欢", "习惯", "希望", "prefer", "preference", "i like");
    }

    private boolean containsSensitiveMemoryContent(String content) {
        return containsAny(content, "密码", "口令", "密钥", "token", "身份证", "银行卡", "信用卡", "手机号",
                "电话号码", "邮箱", "住址", "病史", "诊断", "药物", "医疗", "收入", "财务", "政治立场");
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
        if (result < 0) {
            throw new BizException("写入候选记忆失败");
        }
        return candidate;
    }

    private boolean isImportancePersistable(String importance) {
        String normalized = normalizeImportance(importance);
        return "high".equals(normalized) || "medium".equals(normalized);
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
                .supersededByMemoryId(memory.getSupersededByMemoryId())
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

}
