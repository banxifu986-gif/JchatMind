package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryConsolidationProposalMapper;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.model.entity.UserMemoryConsolidationProposal;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@AllArgsConstructor
public class UserMemoryWriteService {

    static final long DEFAULT_MEMORY_EXPIRATION_DAYS = 365L;
    private static final double SEMANTIC_DUPLICATE_MAX_COSINE_DISTANCE = 0.05D;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UserMemoryMapper userMemoryMapper;
    private final UserMemoryCandidateMapper userMemoryCandidateMapper;
    private final UserMemoryConsolidationProposalMapper consolidationProposalMapper;

    @Transactional
    public void confirmCandidate(String userId, UserMemoryCandidate expected, String content,
                                 boolean update, float[] embedding) {
        UserMemoryCandidate current = userMemoryCandidateMapper.selectByIdAndUserId(expected.getId(), userId);
        if (current == null || !UserMemoryCandidate.STATUS_PENDING.equals(current.getStatus())) {
            throw new BizException("候选记忆状态不可确认");
        }
        if (!Objects.equals(current.getContent(), expected.getContent())
                || !Objects.equals(current.getMemoryType(), expected.getMemoryType())
                || !Objects.equals(current.getSessionId(), expected.getSessionId())
                || !Objects.equals(current.getImportance(), expected.getImportance())
                || !Objects.equals(current.getEvidence(), expected.getEvidence())
                || !Objects.equals(current.getEvidenceMessageId(), expected.getEvidenceMessageId())) {
            throw new BizException("候选记忆已发生变化，请重试");
        }
        if (userMemoryCandidateMapper.markPersistedIfPending(expected.getId(), userId) <= 0) {
            throw new BizException("候选记忆状态不可确认");
        }
        if (findConfirmedMemoryByContent(userId, content) != null) {
            return;
        }
        if (update) {
            handleConflictUpdate(userId, current, content, embedding);
        } else if (!hasSemanticDuplicate(userId, current.getMemoryType(), embedding)) {
            insertConfirmedMemory(userId, current, content, embedding);
        }
    }

    @Transactional
    public void confirmConsolidation(String userId, UserMemoryConsolidationProposal expected, float[] embedding) {
        UserMemoryConsolidationProposal proposal = consolidationProposalMapper
                .selectPendingByIdAndUserId(expected.getId(), userId);
        if (proposal == null) {
            throw new BizException("待处理的整理建议不存在");
        }
        if (!Objects.equals(proposal.getSourceMemoryIdsJson(), expected.getSourceMemoryIdsJson())
                || !Objects.equals(proposal.getSourceFingerprint(), expected.getSourceFingerprint())
                || !Objects.equals(proposal.getProposedType(), expected.getProposedType())
                || !Objects.equals(proposal.getProposedContent(), expected.getProposedContent())) {
            throw new BizException("整理建议已发生变化，请重试");
        }
        List<String> sourceIds = parseSourceMemoryIds(proposal.getSourceMemoryIdsJson());
        List<UserMemory> sources = userMemoryMapper.selectActiveByIdsForUpdate(userId, sourceIds);
        if (sources.size() != sourceIds.size()
                || !proposal.getSourceFingerprint().equals(fingerprintMemories(sources))) {
            consolidationProposalMapper.markStatus(proposal.getId(), userId, "STALE", null);
            return;
        }
        String sourceEvidence = "来源记忆：" + sources.stream()
                .map(UserMemory::getContent).collect(Collectors.joining("；"));
        if (sourceEvidence.length() > 500) {
            sourceEvidence = sourceEvidence.substring(0, 500);
        }
        UserMemoryCandidate source = UserMemoryCandidate.builder()
                .userId(userId).memoryType(proposal.getProposedType()).importance("medium")
                .evidenceMessageId(null).evidence(sourceEvidence).build();
        UserMemory consolidated = insertConfirmedMemory(userId, source, proposal.getProposedContent(), embedding);
        if (sourceIds.contains(consolidated.getId())) {
            throw new BizException("整理建议不能与来源记忆内容相同");
        }
        if (userMemoryMapper.markSupersededByIds(sourceIds, userId, consolidated.getId()) != sourceIds.size()) {
            throw new BizException("来源记忆已发生变化，请重新生成整理建议");
        }
        if (consolidationProposalMapper.markStatus(proposal.getId(), userId, "APPLIED", consolidated.getId()) <= 0) {
            throw new BizException("确认记忆整理建议失败");
        }
    }

    static List<String> parseSourceMemoryIds(String sourceMemoryIdsJson) {
        try {
            List<String> ids = JSON.readValue(sourceMemoryIdsJson, new TypeReference<>() { });
            if (ids.size() < 2 || ids.stream().anyMatch(id -> !StringUtils.hasText(id))) {
                throw new IllegalStateException("source memory ids invalid");
            }
            return ids;
        } catch (JsonProcessingException exception) {
            throw new BizException("整理建议来源无效");
        }
    }

    static String fingerprintMemories(List<UserMemory> memories) {
        String value = memories.stream()
                .sorted(java.util.Comparator.comparing(UserMemory::getId))
                .map(memory -> memory.getId() + "\u0000" + memory.getMemoryType() + "\u0000"
                        + memory.getContent() + "\u0000" + memory.getUpdatedAt())
                .collect(Collectors.joining("\u0001"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    UserMemory insertConfirmedMemory(
            String userId,
            UserMemoryCandidate candidate,
            String content,
            float[] embedding
    ) {
        UserMemory existing = findConfirmedMemoryByContent(userId, content);
        if (existing != null) {
            return existing;
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
        return userMemory;
    }

    private void handleConflictUpdate(String userId, UserMemoryCandidate candidate, String newContent, float[] embedding) {
        UserMemory match = userMemoryMapper.selectActiveByUserIdAndMemoryType(userId, candidate.getMemoryType());

        LocalDateTime now = LocalDateTime.now();
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

    boolean hasSemanticDuplicate(String userId, String memoryType, float[] contentEmbedding) {
        if (contentEmbedding == null || memoryType == null) {
            return false;
        }
        try {
            return userMemoryMapper.existsActiveSemanticDuplicate(
                    userId, memoryType, toVectorLiteral(contentEmbedding), SEMANTIC_DUPLICATE_MAX_COSINE_DISTANCE);
        } catch (Exception e) {
            log.warn("Failed to check semantic memory duplicates: {}", e.getMessage());
            return false;
        }
    }

    private UserMemory findConfirmedMemoryByContent(String userId, String content) {
        return userMemoryMapper.selectByUserIdAndContent(userId, content);
    }

    private String toVectorLiteral(float[] vector) {
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
}
