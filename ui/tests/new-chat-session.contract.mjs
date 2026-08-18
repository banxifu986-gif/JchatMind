import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = readFileSync(
  new URL("../src/components/JChatMindLayout.tsx", import.meta.url),
  "utf8",
);

assert.match(
  source,
  /function SessionScopedAgentChatView[\s\S]*useParams<\{ chatSessionId: string \}>\(\)[\s\S]*<AgentChatView key=\{chatSessionId \?\? "new"\}/,
  "聊天视图必须随会话 ID 重挂载，隔离初始化标记、执行轨迹和流式状态",
);
assert.match(
  source,
  /path="\/chat"[\s\S]*SessionScopedAgentChatView/,
  "新聊天必须使用会话隔离边界",
);
assert.match(
  source,
  /path="\/chat\/:chatSessionId"[\s\S]*SessionScopedAgentChatView/,
  "既有会话必须使用会话隔离边界",
);
