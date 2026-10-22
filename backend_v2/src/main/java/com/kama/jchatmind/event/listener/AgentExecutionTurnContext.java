package com.kama.jchatmind.event.listener;

public final class AgentExecutionTurnContext {

    private static final ThreadLocal<Long> CURRENT_SEQUENCE = new ThreadLocal<>();

    private AgentExecutionTurnContext() {
    }

    public static Long currentSequence() {
        return CURRENT_SEQUENCE.get();
    }

    public static void run(long sequenceNo, Runnable action) {
        Long previous = CURRENT_SEQUENCE.get();
        CURRENT_SEQUENCE.set(sequenceNo);
        try {
            action.run();
        } finally {
            if (previous == null) {
                CURRENT_SEQUENCE.remove();
            } else {
                CURRENT_SEQUENCE.set(previous);
            }
        }
    }
}
