import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const viewSource = readFileSync(
  new URL("../src/components/views/AgentChatView.tsx", import.meta.url),
  "utf8",
);
const historySource = readFileSync(
  new URL("../src/components/views/agentChatView/AgentChatHistory.tsx", import.meta.url),
  "utf8",
);

assert.match(viewSource, /const \[streamingContent, setStreamingContent\] = useState/);
assert.match(viewSource, /message\.type === "AI_CONTENT_DELTA"/);
assert.match(viewSource, /setStreamingContent\(/);
assert.match(historySource, /streamingContent\?: string/);
assert.match(historySource, /streamingContent &&/);
