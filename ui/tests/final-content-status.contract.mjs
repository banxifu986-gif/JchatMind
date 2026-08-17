import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const viewSource = readFileSync(
  new URL("../src/components/views/AgentChatView.tsx", import.meta.url),
  "utf8",
);
const generatedContentStart = viewSource.indexOf('if (message.type === "AI_GENERATED_CONTENT")');
const generatedContentEnd = viewSource.indexOf('if (message.type === "AI_CONTENT_DELTA")', generatedContentStart);
const generatedContentHandler = viewSource.slice(generatedContentStart, generatedContentEnd);

assert.match(
  generatedContentHandler,
  /const isFinalAssistantMessage = message\.payload\.message\.role === "assistant"\s+&& !message\.payload\.message\.metadata\?\.toolCalls\?\.length/,
);
assert.match(
  generatedContentHandler,
  /if \(isFinalAssistantMessage\) \{\s+setDisplayAgentStatus\(false\);\s+setAgentStatusText\(""\);\s+setAgentStatusType\(undefined\);/,
);
