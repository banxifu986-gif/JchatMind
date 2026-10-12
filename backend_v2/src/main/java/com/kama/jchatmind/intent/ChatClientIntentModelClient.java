package com.kama.jchatmind.intent;

import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;

public class ChatClientIntentModelClient implements IntentModelClient {

    public static final String PROMPT_VERSION = "intent-classifier-prompt-v1";

    private static final String SYSTEM_PROMPT = """
            你是 JChatMind 的意图分类器，只负责输出一个 JSON 对象。
            允许的 businessIntent 必须来自服务端枚举；必须输出 businessIntent、0 到 1 的 confidence，以及 top-1 与 top-2 类别置信度之差 margin。
            confidence 表示 top-1 类别置信度；margin 必须是 0 到 1 的数值。
            可选输出 queryIntent、entities 和 requiresClarification。
            candidateCapabilities、authorized、approval、kbIds 等字段不要输出，也不要输出授权判断。
            不要输出 Markdown、解释文本或思维过程。
            """;

    private final ChatClient chatClient;

    public ChatClientIntentModelClient(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
    }

    @Override
    public String classify(String query) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(query == null ? "" : query)
                .call()
                .content();
    }
}
