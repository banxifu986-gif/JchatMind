package com.kama.jchatmind.workflow;

import com.kama.jchatmind.config.ChatClientRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class WorkflowSingleAgentFallbackAnswerService {

    private static final int MAX_EVIDENCE_CHARACTERS = 16_000;

    private final ChatClientRegistry chatClientRegistry;

    public WorkflowSingleAgentFallbackAnswerService(ChatClientRegistry chatClientRegistry) {
        this.chatClientRegistry = chatClientRegistry;
    }

    public String answer(String modelKey, String request, List<WorkflowEvidence> evidence) {
        ChatClient chatClient = chatClientRegistry.get(modelKey);
        if (chatClient == null) {
            throw new IllegalStateException("工作流回退模型不可用");
        }
        StringBuilder context = new StringBuilder();
        if (evidence != null) {
            for (WorkflowEvidence item : evidence) {
                if (item == null || !StringUtils.hasText(item.statement())) {
                    continue;
                }
                String entry = "[" + item.chunkId() + "] " + item.statement() + "\n";
                if (context.length() + entry.length() > MAX_EVIDENCE_CHARACTERS) {
                    break;
                }
                context.append(entry);
            }
        }
        return chatClient.prompt()
                .system("你是单 Agent 回退回答器。只依据提供的证据回答；证据不足时明确说明无法确定，不调用工具。")
                .user("问题：\n" + request + "\n\n可用证据：\n" + context)
                .call()
                .content();
    }
}
