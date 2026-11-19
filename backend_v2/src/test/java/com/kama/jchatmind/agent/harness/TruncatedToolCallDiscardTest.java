package com.kama.jchatmind.agent.harness;

import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.audit.ToolCallRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约依据：specs/003-agent-loop-harness-refactor/contracts/hook-pipeline-contract.md §3（截断判废）。
 * spec 场景 2 验收：finishReason=LENGTH 时全部 toolCall 判废为错误 result、工具实现零执行、逐 call 审计。
 */
class TruncatedToolCallDiscardTest {

    @Test
    void lengthFinishReasonShouldBeDetectedCaseInsensitively() {
        assertTrue(HarnessRunner.isOutputTruncated(chatResponse("length")));
        assertTrue(HarnessRunner.isOutputTruncated(chatResponse("LENGTH")));
        assertFalse(HarnessRunner.isOutputTruncated(chatResponse("stop")));
        assertFalse(HarnessRunner.isOutputTruncated(chatResponse(null)));
        assertFalse(HarnessRunner.isOutputTruncated(null));
    }

    @Test
    void discardedToolCallsShouldBeAuditedPerCall() {
        HarnessProperties properties = new HarnessProperties();
        properties.getAudit().setEnabled(true);
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        HarnessRunner runner = newRunner(properties, auditStore);

        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "KnowledgeTool", "{}"),
                new AssistantMessage.ToolCall("call-2", "function", "sendEmail", "{}")
        );

        String message = runner.recordDiscardedToolCalls(
                "user-1", "agent-1", "session-1", 2, toolCalls);

        assertTrue(message.contains("TOOL_CALLS_TRUNCATED"), "判废消息必须可被模型识别: " + message);
        List<ToolCallRecord> records = auditStore.getRecords("session-1");
        assertEquals(2, records.size(), "每个被判废的 call 恰好一条审计记录");
        assertEquals(ToolCallRecord.Outcome.REJECTED, records.get(0).getOutcome());
        assertEquals("call-1", records.get(0).getToolCallId());
        assertEquals("call-2", records.get(1).getToolCallId());
        assertEquals(2, records.get(0).getStepNumber());
    }

    private HarnessRunner newRunner(HarnessProperties properties, InMemoryAuditStore auditStore) {
        return new HarnessRunner(
                properties,
                new com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor(
                        properties,
                        new com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore(),
                        org.mockito.Mockito.mock(com.kama.jchatmind.webhook.WebhookApprovalOutboxService.class)
                ),
                new com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore(),
                auditStore
        );
    }

    private ChatResponse chatResponse(String finishReason) {
        ChatGenerationMetadata.Builder builder = ChatGenerationMetadata.builder();
        if (finishReason != null) {
            builder.finishReason(finishReason);
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(
                        AssistantMessage.builder().content("partial").build(),
                        builder.build()
                )))
                .build();
    }
}
