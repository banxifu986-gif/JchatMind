package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.dto.AgentExecutionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentExecutionTaskMapper {
    int insert(AgentExecutionTask task);

    List<AgentExecutionTask> selectRunnableSessionHeads(@Param("limit") int limit);

    AgentExecutionTask claim(@Param("id") String id);

    int release(@Param("id") String id);

    int complete(@Param("id") String id);

    int fail(@Param("id") String id, @Param("failureType") String failureType);

    long countByStatus(@Param("status") String status);
}
