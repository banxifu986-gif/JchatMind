package com.kama.jchatmind.service;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        Map<String, KnowledgeBase> knowledgeBaseById = selectKnowledgeBasesByIds(normalizedIds);
        for (String knowledgeBaseId : normalizedIds) {
            KnowledgeBase knowledgeBase = knowledgeBaseById.get(knowledgeBaseId);
            if (knowledgeBase == null || !userId.equals(knowledgeBase.getOwnerId())) {
                throw accessDenied();
            }
        }
        return List.copyOf(normalizedIds);
    }

    public List<KnowledgeBase> filterAccessibleKnowledgeBases(List<String> knowledgeBaseIds, String userId) {
        requireUserId(userId);
        if (CollectionUtils.isEmpty(knowledgeBaseIds)) {
            return List.of();
        }

        List<String> candidateIds = new ArrayList<>();
        for (String knowledgeBaseId : new LinkedHashSet<>(knowledgeBaseIds)) {
            if (!StringUtils.hasText(knowledgeBaseId)) {
                continue;
            }
            candidateIds.add(knowledgeBaseId.trim());
        }
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        Map<String, KnowledgeBase> knowledgeBaseById = selectKnowledgeBasesByIds(candidateIds);
        List<KnowledgeBase> result = new ArrayList<>();
        for (String knowledgeBaseId : candidateIds) {
            KnowledgeBase knowledgeBase = knowledgeBaseById.get(knowledgeBaseId);
            if (knowledgeBase != null && userId.equals(knowledgeBase.getOwnerId())) {
                result.add(knowledgeBase);
            }
        }
        return result;
    }

    private Map<String, KnowledgeBase> selectKnowledgeBasesByIds(Collection<String> knowledgeBaseIds) {
        if (knowledgeBaseIds.isEmpty()) {
            return Map.of();
        }
        return knowledgeBaseMapper.selectByIdBatch(List.copyOf(knowledgeBaseIds)).stream()
                .filter(knowledgeBase -> knowledgeBase != null && StringUtils.hasText(knowledgeBase.getId()))
                .collect(Collectors.toMap(
                        KnowledgeBase::getId,
                        Function.identity(),
                        (first, ignored) -> first
                ));
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
