package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.response.GetUserMemoriesResponse;
import com.kama.jchatmind.model.response.GetUserMemoryCandidatesResponse;

import java.util.List;

public interface UserMemoryFacadeService {
    // 公开接口（从 RequestScopeData 获取 userId）
    GetUserMemoriesResponse getUserMemories();

    GetUserMemoryCandidatesResponse getUserMemoryCandidates();

    void deleteMemory(String memoryId);

    // 内部接口（agent 异步线程调用，需显式传入 userId）
    List<UserMemory> getConfirmedMemories(String userId);

    List<UserMemory> recallRelevantMemories(String userId, String query, int topK);

    void extractMemoryCandidates(String userId, String sessionId);
}
