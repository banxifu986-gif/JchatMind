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
const doneStart = viewSource.indexOf('if (message.type === "AI_DONE")');
const doneEnd = viewSource.indexOf('if (message.type === "AI_ERROR")', doneStart);

assert.match(viewSource, /const \[agentTrace, setAgentTrace\] = useState/);
assert.match(viewSource, /setAgentTrace\(/);
assert.doesNotMatch(
  viewSource.slice(doneStart, doneEnd),
  /setAgentTrace\(\[\]\)/,
  "完成事件不能清空本轮执行轨迹",
);
assert.match(historySource, /agentTrace\?: AgentExecutionTraceItem\[\]/);
assert.match(historySource, /执行轨迹/);
