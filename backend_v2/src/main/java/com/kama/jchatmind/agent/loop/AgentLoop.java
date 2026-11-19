package com.kama.jchatmind.agent.loop;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 循环骨架：状态机、步数预算、next-turn hook、步间取消检查、生命周期事件出口。
 * 零 Spring 依赖，可注入 stub 协作者单测。每步业务由 {@link Phase} 委托实现。
 *
 * 预算语义：步数用尽后仍有一次 finalBudgetTurn（对齐原实现"预算耗尽前的最后一次 think"），
 * 是否判预算耗尽由 Phase 自行决定；取消语义见 contracts/hook-pipeline-contract.md §5。
 */
public final class AgentLoop {

    public enum AgentLoopState { IDLE, RUNNING, FINISHED, FAILED }

    public interface Phase {
        /**
         * 执行一步。返回 true 表示继续下一轮；false 表示本轮结束（正常结束、
         * 终态工具或预算判定由 Phase 内部收尾）。
         */
        boolean step(int stepNumber, boolean finalBudgetTurn);
    }

    public interface Listener {
        void onEvent(AgentLoopEvent event);
    }

    private static final String CANCELLED_REASON = "CANCELLED";

    private final Phase phase;
    private final Listener listener;
    private final AgentCancelState cancelState;
    private final int maxSteps;
    private final AtomicReference<Runnable> beforeTurnHook = new AtomicReference<>();
    private final AtomicReference<AgentLoopState> state = new AtomicReference<>(AgentLoopState.IDLE);
    private volatile String endReason;

    public AgentLoop(Phase phase, Listener listener, AgentCancelState cancelState, int maxSteps) {
        this.phase = phase;
        this.listener = listener;
        this.cancelState = cancelState;
        this.maxSteps = maxSteps;
    }

    public void setBeforeTurnHook(Runnable hook) {
        this.beforeTurnHook.set(hook);
    }

    public AgentCancelState cancelState() {
        return cancelState;
    }

    public AgentLoopState state() {
        return state.get();
    }

    public String endReason() {
        return endReason;
    }

    /**
     * 显式停止：幂等。步间检查负责不再进入下一步，在飞工具由共享
     * {@link AgentCancelState} 的持有方（AgentToolExecutor）中断。
     */
    public void stop() {
        cancelState.cancel();
    }

    public void run() {
        if (!state.compareAndSet(AgentLoopState.IDLE, AgentLoopState.RUNNING)) {
            throw new IllegalStateException("Agent loop is not idle");
        }
        listener.onEvent(AgentLoopEvent.started());
        try {
            for (int i = 0; i <= maxSteps; i++) {
                if (cancelState.isCancelled()) {
                    finishCancelled();
                    return;
                }
                Runnable hook = beforeTurnHook.get();
                if (hook != null) {
                    hook.run();
                    if (cancelState.isCancelled()) {
                        finishCancelled();
                        return;
                    }
                }
                if (!phase.step(i + 1, i >= maxSteps)) {
                    break;
                }
            }
            state.set(AgentLoopState.FINISHED);
            listener.onEvent(AgentLoopEvent.finished());
        } catch (Exception exception) {
            state.set(AgentLoopState.FAILED);
            listener.onEvent(AgentLoopEvent.failed(exception));
            throw new IllegalStateException("Agent loop failed", exception);
        }
    }

    private void finishCancelled() {
        endReason = CANCELLED_REASON;
        state.set(AgentLoopState.FINISHED);
        listener.onEvent(AgentLoopEvent.finished());
    }
}
