package com.kama.jchatmind.agent.loop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约依据：specs/003-agent-loop-harness-refactor/contracts/hook-pipeline-contract.md §5（取消·步间协作）。
 * spec 场景 7 验收：AgentLoop 零 Spring 依赖单测——正常结束 / 预算耗尽 / 中途取消停步 / next-turn hook。
 * 预算语义对齐原实现：步数用尽后仍有一次最终 think 机会（finalBudgetTurn），仅当模型仍要求工具才判 BUDGET_EXHAUSTED。
 */
class AgentLoopTest {

    @Test
    void shouldFinishNormallyWhenPhaseStopsRequestingMoreTurns() {
        // 模拟 3 步：前两步有工具调用，第三步模型不再要求 → 正常结束
        List<Integer> steppedAt = new ArrayList<>();
        List<Boolean> finalTurnFlags = new ArrayList<>();
        AgentLoop.Phase phase = (stepNumber, finalBudgetTurn) -> {
            steppedAt.add(stepNumber);
            finalTurnFlags.add(finalBudgetTurn);
            return stepNumber < 3;
        };
        List<AgentLoopEvent> events = new ArrayList<>();
        AgentLoop loop = new AgentLoop(phase, events::add, new AgentCancelState(), 20);

        loop.run();

        assertEquals(List.of(1, 2, 3), steppedAt);
        assertEquals(List.of(false, false, false), finalTurnFlags);
        assertEquals(AgentLoop.AgentLoopState.FINISHED, loop.state());
        assertEquals(2, events.size());
        assertEquals(AgentLoopEvent.Type.AGENT_STARTED, events.get(0).type());
        assertEquals(AgentLoopEvent.Type.AGENT_FINISHED, events.get(1).type());
    }

    @Test
    void shouldGiveFinalBudgetTurnAfterMaxStepsAndStop() {
        // maxSteps=2：步 1、2 正常；步 3 为 finalBudgetTurn（预算耗尽前的最后机会），phase 返回 false 结束
        List<Integer> steppedAt = new ArrayList<>();
        List<Boolean> finalTurnFlags = new ArrayList<>();
        AgentLoop.Phase phase = (stepNumber, finalBudgetTurn) -> {
            steppedAt.add(stepNumber);
            finalTurnFlags.add(finalBudgetTurn);
            return !finalBudgetTurn;
        };
        AgentLoop loop = new AgentLoop(phase, event -> {
        }, new AgentCancelState(), 2);

        loop.run();

        assertEquals(List.of(1, 2, 3), steppedAt);
        assertEquals(List.of(false, false, true), finalTurnFlags);
        assertEquals(AgentLoop.AgentLoopState.FINISHED, loop.state());
    }

    @Test
    void shouldInvokeBeforeTurnHookBeforeEachTurn() {
        AtomicInteger hookCalls = new AtomicInteger();
        AgentLoop.Phase phase = (stepNumber, finalBudgetTurn) -> stepNumber < 3;
        AgentLoop loop = new AgentLoop(phase, event -> {
        }, new AgentCancelState(), 20);
        loop.setBeforeTurnHook(hookCalls::incrementAndGet);

        loop.run();

        assertEquals(3, hookCalls.get(), "next-turn hook 必须在每轮（含首轮）前调用");
    }

    @Test
    void shouldStopBeforeNextStepWhenCancelled() {
        List<Integer> steppedAt = new ArrayList<>();
        List<Integer> hookCalls = new ArrayList<>();
        AgentCancelState cancelState = new AgentCancelState();
        AgentLoop.Phase phase = (stepNumber, finalBudgetTurn) -> {
            steppedAt.add(stepNumber);
            if (stepNumber == 2) {
                cancelState.cancel();   // 用户在步 2 执行期间发起停止
            }
            return true;
        };
        AgentLoop loop = new AgentLoop(phase, event -> {
        }, cancelState, 20);
        loop.setBeforeTurnHook(() -> hookCalls.add(steppedAt.size()));

        loop.run();

        assertEquals(List.of(1, 2), steppedAt, "取消后不得再进入下一步");
        assertEquals(2, hookCalls.size(), "取消后不得再执行 next-turn hook（步 1、2 前各一次）");
        assertEquals(AgentLoop.AgentLoopState.FINISHED, loop.state());
        assertEquals("CANCELLED", loop.endReason());
    }

    @Test
    void shouldFailAndRethrowWhenPhaseThrows() {
        List<AgentLoopEvent> events = new ArrayList<>();
        AgentLoop.Phase phase = (stepNumber, finalBudgetTurn) -> {
            throw new IllegalStateException("model unavailable");
        };
        AgentLoop loop = new AgentLoop(phase, events::add, new AgentCancelState(), 20);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, loop::run);

        assertTrue(thrown.getCause() instanceof IllegalStateException);
        assertEquals(AgentLoop.AgentLoopState.FAILED, loop.state());
        assertEquals(2, events.size());
        assertEquals(AgentLoopEvent.Type.AGENT_STARTED, events.get(0).type());
        assertEquals(AgentLoopEvent.Type.AGENT_FAILED, events.get(1).type());
    }

    @Test
    void shouldRejectRunWhenNotIdle() {
        AgentLoop loop = new AgentLoop((stepNumber, finalBudgetTurn) -> false, event -> {
        }, new AgentCancelState(), 20);
        loop.run();

        assertThrows(IllegalStateException.class, loop::run);
    }
}
