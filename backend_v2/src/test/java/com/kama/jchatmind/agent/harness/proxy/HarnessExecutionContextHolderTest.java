package com.kama.jchatmind.agent.harness.proxy;

import com.kama.jchatmind.agent.harness.HarnessContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HarnessExecutionContextHolderTest {

    @AfterEach
    void tearDown() {
        HarnessExecutionContextHolder.clear();
    }

    @Test
    void shouldPollSameSignatureInBindingOrder() {
        HarnessContext first = HarnessContext.builder()
                .sessionId("session-1")
                .agentId("agent-1")
                .userId("user-1")
                .toolCallId("call-1")
                .toolName("sendEmail")
                .toolInput("{\"to\":\"a\"}")
                .stepNumber(1)
                .build();
        HarnessContext second = HarnessContext.builder()
                .sessionId("session-1")
                .agentId("agent-1")
                .userId("user-1")
                .toolCallId("call-2")
                .toolName("sendEmail")
                .toolInput("{\"to\":\"a\"}")
                .stepNumber(1)
                .build();

        HarnessExecutionContextHolder.bind(
                List.of(first, second),
                new HarnessExecutionContextHolder.BatchMetadata("session-1", "agent-1", "user-1", 1)
        );

        assertEquals("call-1", HarnessExecutionContextHolder.poll("sendEmail", "{\"to\":\"a\"}").getToolCallId());
        assertEquals("call-2", HarnessExecutionContextHolder.poll("sendEmail", "{\"to\":\"a\"}").getToolCallId());
        assertNull(HarnessExecutionContextHolder.poll("sendEmail", "{\"to\":\"a\"}"));
    }
}
