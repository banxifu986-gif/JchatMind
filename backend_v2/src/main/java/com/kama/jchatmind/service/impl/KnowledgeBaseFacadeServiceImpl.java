package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.request.CreateKnowledgeBaseRequest;
import com.kama.jchatmind.model.request.UpdateKnowledgeBaseRequest;
import com.kama.jchatmind.model.response.CreateKnowledgeBaseResponse;
import com.kama.jchatmind.model.response.GetKnowledgeBasesResponse;
import com.kama.jchatmind.model.vo.KnowledgeBaseVO;
import com.kama.jchatmind.service.KnowledgeBaseFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class KnowledgeBaseFacadeServiceImpl implements KnowledgeBaseFacadeService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final RequestScopeData requestScopeData;

    @Override
    public GetKnowledgeBasesResponse getKnowledgeBases() {
        List<KnowledgeBase> knowledgeBases = knowledgeBaseAccessService.getOwnedKnowledgeBases(requireUserId());
        List<KnowledgeBaseVO> result = new ArrayList<>();
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            try {
                KnowledgeBaseVO vo = knowledgeBaseConverter.toVO(knowledgeBase);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetKnowledgeBasesResponse.builder()
                .knowledgeBases(result.toArray(new KnowledgeBaseVO[0]))
                .build();
    }

    @Override
    public CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        try {
            String userId = requireUserId();
            // 将 CreateKnowledgeBaseRequest 转换为 KnowledgeBaseDTO
            KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(request);
            
            // 将 KnowledgeBaseDTO 转换为 KnowledgeBase 实体
            KnowledgeBase knowledgeBase = knowledgeBaseConverter.toEntity(knowledgeBaseDTO);
            knowledgeBase.setOwnerId(userId);
            
            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            knowledgeBase.setCreatedAt(now);
            knowledgeBase.setUpdatedAt(now);
            
            // 插入数据库，ID 由数据库自动生成
            int result = knowledgeBaseMapper.insert(knowledgeBase);
            if (result <= 0) {
                throw new BizException("创建知识库失败");
            }
            
            // 返回生成的 knowledgeBaseId
            return CreateKnowledgeBaseResponse.builder()
                    .knowledgeBaseId(knowledgeBase.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建知识库时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseAccessService.requireAccessibleKnowledgeBase(knowledgeBaseId, requireUserId());
        
        int result = knowledgeBaseMapper.deleteById(knowledgeBaseId);
        if (result <= 0) {
            throw new BizException("删除知识库失败");
        }
    }

    @Override
    public void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        try {
            KnowledgeBase existingKnowledgeBase = knowledgeBaseAccessService
                    .requireAccessibleKnowledgeBase(knowledgeBaseId, requireUserId());
            
            // 将现有 KnowledgeBase 转换为 KnowledgeBaseDTO
            KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(existingKnowledgeBase);
            
            // 使用 UpdateKnowledgeBaseRequest 更新 KnowledgeBaseDTO
            knowledgeBaseConverter.updateDTOFromRequest(knowledgeBaseDTO, request);
            
            // 将更新后的 KnowledgeBaseDTO 转换回 KnowledgeBase 实体
            KnowledgeBase updatedKnowledgeBase = knowledgeBaseConverter.toEntity(knowledgeBaseDTO);
            
            // 保留原有的 ID 和创建时间
            updatedKnowledgeBase.setId(existingKnowledgeBase.getId());
            updatedKnowledgeBase.setOwnerId(existingKnowledgeBase.getOwnerId());
            updatedKnowledgeBase.setCreatedAt(existingKnowledgeBase.getCreatedAt());
            updatedKnowledgeBase.setUpdatedAt(LocalDateTime.now());
            
            // 更新数据库
            int result = knowledgeBaseMapper.updateById(updatedKnowledgeBase);
            if (result <= 0) {
                throw new BizException("更新知识库失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新知识库时发生序列化错误: " + e.getMessage());
        }
    }

    private String requireUserId() {
        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            throw new BizException("用户未登录");
        }
        return String.valueOf(userId);
    }
}
