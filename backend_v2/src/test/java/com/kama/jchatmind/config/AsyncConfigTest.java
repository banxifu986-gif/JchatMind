package com.kama.jchatmind.config;

import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.event.listener.ChatEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void shouldBindChatEventHandlingToDedicatedBoundedAgentExecutor() throws NoSuchMethodException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            assertThat(context.containsBean("agentTaskExecutor")).isTrue();
            ThreadPoolTaskExecutor executor = context.getBean("agentTaskExecutor", ThreadPoolTaskExecutor.class);

            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue())
                    .isInstanceOf(BlockingQueue.class);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(50);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("agent-event-");

            Method handle = ChatEventListener.class.getDeclaredMethod("handle", ChatEvent.class);
            assertThat(handle.getAnnotation(Async.class).value()).isEqualTo("agentTaskExecutor");
        }
    }
}
