package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.dto.AgentKnowledgeBaseBindingDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentKnowledgeBaseMapper {

    List<String> selectKbIdsByAgentId(String agentId);

    List<AgentKnowledgeBaseBindingDTO> selectKbIdsByAgentIds(@Param("agentIds") List<String> agentIds);

    int deleteByAgentId(String agentId);

    int insertBatch(
            @Param("agentId") String agentId,
            @Param("kbIds") List<String> kbIds,
            @Param("boundByUserId") String boundByUserId
    );
}
