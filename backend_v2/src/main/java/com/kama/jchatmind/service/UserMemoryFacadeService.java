package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.response.GetUserMemoriesResponse;
import com.kama.jchatmind.model.response.GetUserMemoryCandidatesResponse;

import java.util.List;

public interface UserMemoryFacadeService {
    // 公开接口（从 RequestScopeData 获取 userId）
    GetUserMemoriesResponse getUserMemories();

    GetUserMemoryCandidatesResponse getUserMemoryCandidates();

    void confirmUserMemoryCandidate(String candidateId);

    void discardUserMemoryCandidate(String candidateId);

    void deleteMemory(String memoryId);

    void updateMemory(String memoryId, String content);

    void updateMemoryExpiration(String memoryId, java.time.LocalDateTime expiresAt);

    void clearUserMemories();

    // 内部接口（agent 异步线程调用，需显式传入 userId）
    List<UserMemory> getConfirmedMemories(String userId);

    List<UserMemory> recallRelevantMemories(String userId, String query, int topK);

    MemoryExtractionResult extractMemoryCandidates(String userId, String sessionId);
}
