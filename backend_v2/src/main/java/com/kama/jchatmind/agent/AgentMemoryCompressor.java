package com.kama.jchatmind.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
final class AgentMemoryCompressor {
    private static final int COMPRESSION_CHAR_THRESHOLD = 8000;
    private static final int KEEP_RECENT_MESSAGES = 8;
    private static final int CONVERSATION_SUMMARY_CHAR_LIMIT = 4000;

    private AgentMemoryCompressor() {
    }

    static String compress(ChatClient client, List<Message> allMessages, String conversationSummary) {
        int totalChars = allMessages.stream()
                .mapToInt(m -> messageText(m) != null ? messageText(m).length() : 0)
                .sum();
        if (totalChars < COMPRESSION_CHAR_THRESHOLD) {
            return conversationSummary;
        }

        int keepFrom = Math.max(1, allMessages.size() - KEEP_RECENT_MESSAGES);
        if (keepFrom <= 1) {
            return conversationSummary;
        }
        keepFrom = adjustKeepFromForToolPairs(allMessages, keepFrom);
        if (keepFrom <= 1) {
            return conversationSummary;
        }

        try {
            List<Message> toCompress = new ArrayList<>(allMessages.subList(1, keepFrom));
            String newSummary = generateSummary(client, toCompress, conversationSummary);
            if (!StringUtils.hasText(newSummary)) {
                return conversationSummary;
            }
            conversationSummary = limitSummary(StringUtils.hasText(conversationSummary)
                    ? conversationSummary + "\n" + newSummary
                    : newSummary);

            allMessages.subList(1, keepFrom).clear();
            allMessages.add(1, new SystemMessage("【对话历史摘要】\n" + conversationSummary));
            log.info("Memory compressed: {} messages summarized, total chars before: {}", toCompress.size(), totalChars);
        } catch (Exception e) {
            log.warn("Failed to compress memory", e);
        }
        return conversationSummary;
    }

    private static int adjustKeepFromForToolPairs(List<Message> messages, int keepFrom) {
        int adjusted = keepFrom;
        for (int i = keepFrom; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage) {
                for (int j = i - 1; j >= 1; j--) {
                    if (messages.get(j) instanceof AssistantMessage am
                            && am.getToolCalls() != null
                            && !am.getToolCalls().isEmpty()) {
                        adjusted = Math.min(adjusted, j);
                        break;
                    }
                }
            }
        }
        return adjusted;
    }

    private static String messageText(Message msg) {
        if (msg instanceof AssistantMessage am) {
            return am.getText();
        }
        if (msg instanceof UserMessage um) {
            return um.getText();
        }
        if (msg instanceof SystemMessage sm) {
            return sm.getText();
        }
        if (msg instanceof ToolResponseMessage trm) {
            return trm.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        return "";
    }

    private static String generateSummary(ChatClient client, List<Message> toSummarize, String conversationSummary) {
        StringBuilder messagesText = new StringBuilder();
        for (Message msg : toSummarize) {
            String content = messageText(msg);
            if (content == null || content.isEmpty()) {
                continue;
            }
            if (content.length() > 1000) {
                content = content.substring(0, 1000) + "...";
            }
            messagesText.append(content).append("\n");
        }

        String boundedConversationSummary = limitSummary(conversationSummary);
        String summaryPrompt = StringUtils.hasText(boundedConversationSummary)
                ? "上一轮总结: " + boundedConversationSummary + "\n\n对话:\n" + messagesText
                : "对话:\n" + messagesText;

        try {
            String response = client.prompt()
                    .system("将以下对话总结为2-3句话，聚焦关键话题、用户意图和重要决策。只输出总结文本。")
                    .user(summaryPrompt)
                    .call()
                    .content();
            return response != null ? response.trim() : null;
        } catch (Exception e) {
            log.warn("Failed to generate summary", e);
            return null;
        }
    }

    static String limitSummary(String summary) {
        if (!StringUtils.hasText(summary) || summary.length() <= CONVERSATION_SUMMARY_CHAR_LIMIT) {
            return summary;
        }
        return summary.substring(summary.length() - CONVERSATION_SUMMARY_CHAR_LIMIT);
    }

}
