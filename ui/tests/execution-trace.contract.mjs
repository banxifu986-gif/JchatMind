import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const hookSource = readFileSync(
  new URL("../src/hooks/useChatStream.ts", import.meta.url),
  "utf8",
);
const historySource = readFileSync(
  new URL("../src/components/views/agentChatView/AgentChatHistory.tsx", import.meta.url),
  "utf8",
);
const doneStart = hookSource.indexOf('if (message.type === "AI_DONE")');
const doneEnd = hookSource.indexOf('if (message.type === "AI_ERROR")', doneStart);

assert.match(hookSource, /const \[agentTrace, setAgentTrace\] = useState/);
assert.match(hookSource, /setAgentTrace\(/);
assert.doesNotMatch(
  hookSource.slice(doneStart, doneEnd),
  /setAgentTrace\(\[\]\)/,
  "完成事件不能清空本轮执行轨迹",
);
assert.match(historySource, /agentTrace\?: AgentExecutionTraceItem\[\]/);
assert.match(historySource, /执行轨迹/);
