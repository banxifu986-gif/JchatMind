package com.kama.jchatmind.agent.loop;

/**
 * AgentLoop 生命周期事件（内部模型，经 SSE 桥映射为既有线上契约）。
 */
public record AgentLoopEvent(Type type, String detail, Throwable error) {

    public enum Type {
        AGENT_STARTED,
        AGENT_FINISHED,
        AGENT_FAILED
    }

    public static AgentLoopEvent started() {
        return new AgentLoopEvent(Type.AGENT_STARTED, null, null);
    }

    public static AgentLoopEvent finished() {
        return new AgentLoopEvent(Type.AGENT_FINISHED, null, null);
    }

    public static AgentLoopEvent failed(Throwable error) {
        return new AgentLoopEvent(Type.AGENT_FAILED, null, error);
    }
}
