package com.kama.jchatmind.agent.loop;

/**
 * run 级取消状态：AgentLoop 步间检查与 AgentToolExecutor 在飞中断共享同一实例。
 * cancel() 幂等。
 */
public final class AgentCancelState {

    private volatile boolean cancelled;

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
