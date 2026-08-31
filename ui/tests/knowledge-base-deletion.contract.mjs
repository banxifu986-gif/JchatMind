import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const apiSource = readFileSync(new URL("../src/api/api.ts", import.meta.url), "utf8");
const hookSource = readFileSync(
  new URL("../src/hooks/useKnowledgeBases.ts", import.meta.url),
  "utf8",
);
const tabSource = readFileSync(
  new URL("../src/components/tabs/KnowledgeBaseTabContent.tsx", import.meta.url),
  "utf8",
);
const sideMenuSource = readFileSync(
  new URL("../src/components/SideMenu.tsx", import.meta.url),
  "utf8",
);

assert.match(apiSource, /export async function deleteKnowledgeBase/, "必须提供知识库删除 API");
assert.match(
  apiSource,
  /`\/knowledge-bases\/\$\{knowledgeBaseId\}`/,
  "删除 API 必须使用知识库资源路由",
);
assert.match(
  apiSource,
  /export async function getKnowledgeBaseDeletionTask/,
  "必须提供删除任务查询 API",
);
assert.match(
  apiSource,
  /`\/knowledge-base-deletion-tasks\/\$\{deletionTaskId\}`/,
  "删除任务查询必须使用受控任务路由",
);
assert.match(hookSource, /deleteKnowledgeBaseHandle/, "知识库 Hook 必须暴露删除操作");
assert.match(hookSource, /getKnowledgeBaseDeletionTask/, "删除操作必须查询最终任务状态");
assert.match(hookSource, /SUCCEEDED|DEAD_LETTER/, "删除任务必须识别终态");
assert.match(tabSource, /onDeleteKnowledgeBase/, "知识库列表必须接收删除回调");
assert.match(tabSource, /Popconfirm/, "删除知识库必须二次确认");
assert.match(tabSource, /删除知识库/, "删除确认必须明确知识库对象");
assert.match(sideMenuSource, /onDeleteKnowledgeBase/, "侧边栏必须接入知识库删除操作");
