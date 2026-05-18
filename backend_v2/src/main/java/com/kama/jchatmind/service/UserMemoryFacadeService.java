package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.response.GetUserMemoriesResponse;
import com.kama.jchatmind.model.response.GetUserMemoryCandidatesResponse;

import java.util.List;

public interface UserMemoryFacadeService {
    GetUserMemoriesResponse getUserMemories(String userId);

    GetUserMemoryCandidatesResponse getUserMemoryCandidates(String userId);

    void confirmCandidate(String userId, String candidateId);

    void deleteMemory(String userId, String memoryId);

    List<UserMemory> getConfirmedMemories(String userId);

    void extractMemoryCandidates(String userId, String sessionId);
}
