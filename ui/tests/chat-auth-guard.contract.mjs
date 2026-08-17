import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = readFileSync(
  new URL("../src/components/views/AgentChatView.tsx", import.meta.url),
  "utf8",
);
const handlerStart = source.indexOf("const handleSendMessage");
const handlerEnd = source.indexOf("const handleApproval", handlerStart);
const handler = source.slice(handlerStart, handlerEnd);
const loginGuard = handler.indexOf('antdMessage.warning("请先登录")');
const existingSessionBranch = handler.indexOf("if (!chatSessionId)");

assert.ok(loginGuard >= 0, "聊天发送必须提示未登录用户先登录");
assert.ok(
  loginGuard < existingSessionBranch,
  "登录校验必须位于会话分支之前，覆盖已有聊天会话的发送",
);
