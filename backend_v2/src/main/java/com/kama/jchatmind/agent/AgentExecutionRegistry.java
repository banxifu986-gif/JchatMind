package com.kama.jchatmind.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中 Agent 实例的会话级注册表：停止端点据此定位目标会话的活动 agent。
 * 仅跟踪当前执行中的实例（排队任务在开始执行时才注册），stop 幂等。
 */
@Slf4j
@Component
public class AgentExecutionRegistry {

    private final Map<String, JChatMind> runningBySession = new ConcurrentHashMap<>();

    public void register(String sessionId, JChatMind agent) {
        runningBySession.put(sessionId, agent);
    }

    public void unregister(String sessionId, JChatMind agent) {
        runningBySession.remove(sessionId, agent);
    }

    /** 停止该会话当前运行中的 agent；无运行中实例时为幂等 no-op。返回是否命中。 */
    public boolean stop(String sessionId) {
        JChatMind agent = runningBySession.get(sessionId);
        if (agent == null) {
            return false;
        }
        log.info("Stopping agent execution: sessionId={}", sessionId);
        agent.stop();
        return true;
    }
}
