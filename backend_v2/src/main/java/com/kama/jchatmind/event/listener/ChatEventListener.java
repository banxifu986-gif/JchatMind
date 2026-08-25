package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@Slf4j
public class ChatEventListener {

    private final JChatMindFactory jChatMindFactory;
    private final UserMemoryFacadeService userMemoryFacadeService;
    private final ChatSessionExecutionCoordinator chatSessionExecutionCoordinator;
    private final MemoryExtractionFailureRegistry memoryExtractionFailureRegistry;

    @Async("agentTaskExecutor")
    @EventListener
    public void handle(ChatEvent event) {
        chatSessionExecutionCoordinator.execute(event.getSessionId(), () -> handleInSession(event));
    }

    private void handleInSession(ChatEvent event) {
        try {
            JChatMind jChatMind = jChatMindFactory.create(
                    event.getUserId(),
                    event.getAgentId(),
                    event.getSessionId()
            );
            jChatMind.run();
        } finally {
            try {
                MemoryExtractionResult extractionResult = userMemoryFacadeService.extractMemoryCandidates(
                        event.getUserId(),
                        event.getSessionId()
                );
                if (extractionResult == MemoryExtractionResult.EXTRACTED) {
                    memoryExtractionFailureRegistry.clear(event.getUserId(), event.getSessionId());
                }
            } catch (Exception e) {
                memoryExtractionFailureRegistry.recordFailure(event.getUserId(), event.getSessionId(), e);
                log.warn("Failed to extract memory candidates for session {}: {}",
                        event.getSessionId(), e.getClass().getName());
            }
        }
    }
}
