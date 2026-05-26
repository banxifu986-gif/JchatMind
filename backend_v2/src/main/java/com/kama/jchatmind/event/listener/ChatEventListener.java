package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
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

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        try {
            JChatMind jChatMind = jChatMindFactory.create(
                    event.getUserId(),
                    event.getAgentId(),
                    event.getSessionId()
            );
            jChatMind.run();
        } finally {
            try {
                userMemoryFacadeService.extractMemoryCandidates(event.getUserId(), event.getSessionId());
            } catch (Exception e) {
                log.warn("Failed to extract memory candidates for session {}", event.getSessionId(), e);
            }
        }
    }
}
