package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.CreateKnowledgeBaseRequest;
import com.kama.jchatmind.model.request.UpdateKnowledgeBaseRequest;
import com.kama.jchatmind.model.response.CreateKnowledgeBaseResponse;
import com.kama.jchatmind.model.response.DeleteKnowledgeBaseResponse;
import com.kama.jchatmind.model.response.GetKnowledgeBaseDeletionTaskResponse;
import com.kama.jchatmind.model.response.GetKnowledgeBasesResponse;

public interface KnowledgeBaseFacadeService {
    GetKnowledgeBasesResponse getKnowledgeBases();

    CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request);

    DeleteKnowledgeBaseResponse deleteKnowledgeBase(String knowledgeBaseId);

    GetKnowledgeBaseDeletionTaskResponse getKnowledgeBaseDeletionTask(String taskId);

    void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request);
}

