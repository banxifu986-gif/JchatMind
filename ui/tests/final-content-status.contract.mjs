import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const hookSource = readFileSync(
  new URL("../src/hooks/useChatStream.ts", import.meta.url),
  "utf8",
);
const generatedContentStart = hookSource.indexOf('if (message.type === "AI_GENERATED_CONTENT")');
const generatedContentEnd = hookSource.indexOf('if (message.type === "AI_CONTENT_DELTA")', generatedContentStart);
const generatedContentHandler = hookSource.slice(generatedContentStart, generatedContentEnd);

assert.match(
  generatedContentHandler,
  /const isFinalAssistantMessage = message\.payload\.message\.role === "assistant"\s+&& !message\.payload\.message\.metadata\?\.toolCalls\?\.length/,
);
assert.match(
  generatedContentHandler,
  /if \(isFinalAssistantMessage\) \{\s+setDisplayAgentStatus\(false\);\s+setAgentStatusText\(""\);\s+setAgentStatusType\(undefined\);/,
);
