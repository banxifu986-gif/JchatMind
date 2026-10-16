import type { ChatMessageVO } from "../types/index.ts";

export function mergeChatHistoryWithLiveMessages(
  history: ChatMessageVO[],
  liveMessages: ChatMessageVO[],
): ChatMessageVO[] {
  const messagesById = new Map(history.map((message) => [message.id, message]));
  for (const message of liveMessages) {
    if (!messagesById.has(message.id)) {
      messagesById.set(message.id, message);
    }
  }
  return [...messagesById.values()];
}
