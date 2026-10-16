package com.kama.jchatmind;

import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.impl.RedisChatSseEventStore;
import com.kama.jchatmind.service.impl.SseServiceImpl;
import org.springframework.context.ConfigurableApplicationContext;

public final class G5FullApplicationSsePublisherProcessProbe {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("A session ID is required");
        }
        String sessionId = args[0];
        try (ConfigurableApplicationContext context =
                     G5FullApplicationHttpPreflightL2Test.startSecondFullApplication()) {
            SseServiceImpl sender = context.getBean(SseServiceImpl.class);
            for (int attempt = 0; attempt < 20; attempt++) {
                sender.send(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.AI_THINKING)
                        .payload(SseMessage.Payload.builder()
                                .statusText("full-app-separate-jvm-" + attempt)
                                .build())
                        .build());
                Thread.sleep(500);
            }
            long sequence = context.getBean(RedisChatSseEventStore.class).latestSequence(sessionId);
            System.out.println("G5_FULL_APP_PUBLISHED:" + sequence);
        }
    }
}
