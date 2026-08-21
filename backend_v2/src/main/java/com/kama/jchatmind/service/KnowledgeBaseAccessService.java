package com.kama.jchatmind.service;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseAccessService {

    private static final String ACCESS_DENIED_MESSAGE = "无权访问知识库";

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public List<KnowledgeBase> getOwnedKnowledgeBases(String userId) {
        String requiredUserId = requireUserId(userId);
        return knowledgeBaseMapper.selectByOwnerId(requiredUserId).stream()
                .filter(knowledgeBase -> knowledgeBase != null && requiredUserId.equals(knowledgeBase.getOwnerId()))
                .toList();
    }

    public KnowledgeBase requireAccessibleKnowledgeBase(String knowledgeBaseId, String userId) {
        requireUserId(userId);
        if (!StringUtils.hasText(knowledgeBaseId)) {
            throw accessDenied();
        }
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId.trim());
        if (knowledgeBase == null || !userId.equals(knowledgeBase.getOwnerId())) {
            throw accessDenied();
        }
        return knowledgeBase;
    }

    public List<String> requireAccessibleKnowledgeBaseIds(List<String> knowledgeBaseIds, String userId) {
        requireUserId(userId);
        if (CollectionUtils.isEmpty(knowledgeBaseIds)) {
            return List.of();
        }

        Set<String> normalizedIds = new LinkedHashSet<>();
        for (String knowledgeBaseId : knowledgeBaseIds) {
            if (!StringUtils.hasText(knowledgeBaseId)) {
                throw accessDenied();
            }
            normalizedIds.add(knowledgeBaseId.trim());
        }

        for (String knowledgeBaseId : normalizedIds) {
            requireAccessibleKnowledgeBase(knowledgeBaseId, userId);
        }
        return List.copyOf(normalizedIds);
    }

    public List<KnowledgeBase> filterAccessibleKnowledgeBases(List<String> knowledgeBaseIds, String userId) {
        requireUserId(userId);
        if (CollectionUtils.isEmpty(knowledgeBaseIds)) {
            return List.of();
        }

        List<KnowledgeBase> result = new ArrayList<>();
        for (String knowledgeBaseId : new LinkedHashSet<>(knowledgeBaseIds)) {
            if (!StringUtils.hasText(knowledgeBaseId)) {
                continue;
            }
            KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId.trim());
            if (knowledgeBase != null && userId.equals(knowledgeBase.getOwnerId())) {
                result.add(knowledgeBase);
            }
        }
        return result;
    }

    private String requireUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BizException("用户未登录");
        }
        return userId;
    }

    private BizException accessDenied() {
        return new BizException(ACCESS_DENIED_MESSAGE);
    }
}
