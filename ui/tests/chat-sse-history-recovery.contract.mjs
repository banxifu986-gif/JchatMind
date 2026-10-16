import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const viewSource = readFileSync(
  new URL("../src/components/views/AgentChatView.tsx", import.meta.url),
  "utf8",
);

assert.match(
  viewSource,
  /const historyRecoveryMessagesRef = useRef<ChatMessageVO\[\] \| null>\(null\)/,
  "SSE messages arriving during history recovery must be buffered",
);
assert.match(
  viewSource,
  /historyRecoveryMessagesRef\.current\?\.push\(message\)/,
  "live chat messages must be retained while the history request is pending",
);
assert.match(
  viewSource,
  /mergeChatHistoryWithLiveMessages\(resp\.chatMessages, liveMessagesDuringRecovery\)/,
  "the recovered history must include live messages received during the request",
);
assert.match(
  viewSource,
  /getChatMessages\(recoveryMessages\)/,
  "the replay-gap recovery must pass its message buffer into the history refresh",
);

const { mergeChatHistoryWithLiveMessages } = await import("../src/utils/chatHistory.ts");
const persistedMessage = { id: "persisted", content: "history" };
const liveMessage = { id: "live", content: "arrived during recovery" };
const merged = mergeChatHistoryWithLiveMessages([persistedMessage], [liveMessage]);

assert.deepEqual(merged, [persistedMessage, liveMessage]);
