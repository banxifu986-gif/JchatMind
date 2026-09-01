import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const sideMenuSource = readFileSync(
  new URL("../src/components/SideMenu.tsx", import.meta.url),
  "utf8",
);
const emptyViewSource = readFileSync(
  new URL("../src/components/views/agentChatView/EmptyAgentChatView.tsx", import.meta.url),
  "utf8",
);

assert.match(
  sideMenuSource,
  /useChatSessions[\s\S]*最近对话[\s\S]*工作区/,
  "工作台侧栏必须直接展示最近会话，并提供工作区入口",
);
assert.match(
  sideMenuSource,
  /chatSessionId[\s\S]*pathname[\s\S]*isActive/,
  "当前会话必须根据路由路径高亮",
);
assert.match(
  emptyViewSource,
  /你想让 JChatMind 帮你构建什么？[\s\S]*QUICK_PROMPTS[\s\S]*setMessage\(prompt\)/,
  "空聊天页必须提供品牌欢迎语和只填充输入框的快捷提示",
);
