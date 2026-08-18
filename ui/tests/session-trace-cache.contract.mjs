import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const layoutSource = readFileSync(
  new URL("../src/components/JChatMindLayout.tsx", import.meta.url),
  "utf8",
);
const chatViewSource = readFileSync(
  new URL("../src/components/views/AgentChatView.tsx", import.meta.url),
  "utf8",
);

assert.match(
  layoutSource,
  /const \[sessionTraceCache\] = useState\(\s*\(\) => new Map<string, AgentExecutionTraceItem\[\]>\(\),\s*\);/,
  "聊天布局必须按会话保存已收到的执行轨迹",
);
assert.match(
  layoutSource,
  /<AgentChatView key=\{chatSessionId \?\? "new"\} sessionTraceCache=\{sessionTraceCache\} \/>/,
  "会话重挂载时必须把会话级轨迹缓存交给聊天视图",
);
assert.match(
  chatViewSource,
  /sessionTraceCache\?\.get\(chatSessionId\)/,
  "聊天视图首次挂载时必须恢复当前会话的轨迹",
);
assert.match(
  chatViewSource,
  /const \[agentTrace, setAgentTrace\] = useState<AgentExecutionTraceItem\[\]>\(initialAgentTrace\);/,
  "恢复的会话轨迹必须作为视图初始 state，而非只读取不使用",
);
assert.match(
  chatViewSource,
  /useEffect\(\(\) => \{\s*if \(chatSessionId\) \{\s*sessionTraceCache\?\.set\(chatSessionId, agentTrace\);/,
  "已提交的会话轨迹必须在 effect 中同步到缓存",
);
const recordAgentTrace = chatViewSource.slice(
  chatViewSource.indexOf("const recordAgentTrace"),
  chatViewSource.indexOf("const getChatMessages"),
);
assert.doesNotMatch(
  recordAgentTrace,
  /traceSequenceRef|sessionTraceCache\?\.set/,
  "状态 updater 不能递增 ref 或写入缓存等副作用",
);
assert.match(
  recordAgentTrace,
  /id: \(previousTrace\.at\(-1\)\?\.id \?\? -1\) \+ 1/,
  "切回截断轨迹后必须从当前最后一条 ID 推导下一条 ID",
);
assert.match(
  chatViewSource,
  /setAgentTrace\(\[\]\);\s*sessionTraceCache\?\.set\(chatSessionId, \[\]\);/,
  "发送新一轮消息时只能清空当前会话的执行轨迹缓存",
);
