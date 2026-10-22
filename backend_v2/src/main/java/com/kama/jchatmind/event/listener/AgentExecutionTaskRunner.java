package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.event.ChatEvent;

public interface AgentExecutionTaskRunner {
    void runPersistedTask(ChatEvent event);
}
