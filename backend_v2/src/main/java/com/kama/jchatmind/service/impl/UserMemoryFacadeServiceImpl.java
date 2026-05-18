package com.kama.jchatmind.service.impl;

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
import com.kama.jchatmind.service.UserMemoryFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@AllArgsConstructor
public class UserMemoryFacadeServiceImpl implements UserMemoryFacadeService {

    private final UserMemoryMapper userMemoryMapper;
    private final UserMemoryCandidateMapper userMemoryCandidateMapper;
    private final ChatMessageFacadeService chatMessageFacadeService;

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

        if (userMemoryMapper.selectByUserIdAndContent(validatedUserId, candidate.getContent()) == null) {
            LocalDateTime now = LocalDateTime.now();
            UserMemory userMemory = UserMemory.builder()
                    .userId(validatedUserId)
                    .sessionId(candidate.getSessionId())
                    .memoryType(candidate.getMemoryType())
                    .content(candidate.getContent())
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
    public void extractMemoryCandidates(String userId, String sessionId) {
        String validatedUserId = requireUserId(userId);
        if (!StringUtils.hasText(sessionId)) {
            return;
        }

        List<ChatMessageDTO> recentMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(
                validatedUserId,
                sessionId,
                8
        );
        for (ChatMessageDTO chatMessage : recentMessages) {
            if (chatMessage.getRole() != ChatMessageDTO.RoleType.USER) {
                continue;
            }
            for (ExtractedMemory extractedMemory : extractFromText(chatMessage.getContent())) {
                persistCandidateIfAbsent(validatedUserId, sessionId, extractedMemory);
            }
        }
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
                .evidence(extractedMemory.evidence())
                .createdAt(now)
                .updatedAt(now)
                .build();
        userMemoryCandidateMapper.insert(candidate);
    }

    private List<ExtractedMemory> extractFromText(String content) {
        List<ExtractedMemory> result = new ArrayList<>();
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
            result.add(new ExtractedMemory(memoryType, segment, segment));
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

    private UserMemoryVO toMemoryVO(UserMemory memory) {
        return UserMemoryVO.builder()
                .id(memory.getId())
                .userId(memory.getUserId())
                .sessionId(memory.getSessionId())
                .memoryType(memory.getMemoryType())
                .content(memory.getContent())
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
                .build();
    }

    private String requireUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BizException("userId 不能为空");
        }
        return userId.trim();
    }

    private record ExtractedMemory(String memoryType, String content, String evidence) {
    }
}
