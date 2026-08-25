import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const apiSource = readFileSync(new URL("../src/api/api.ts", import.meta.url), "utf8");
const viewSource = readFileSync(
  new URL("../src/components/views/UserMemoryView.tsx", import.meta.url),
  "utf8",
);
const editStart = apiSource.indexOf("export async function updateUserMemory");
const editEnd = apiSource.indexOf("export async function deleteUserMemory", editStart);
const editApi = apiSource.slice(editStart, editEnd);
const handlerStart = viewSource.indexOf("const handleEditMemory");
const handlerEnd = viewSource.indexOf("return (", handlerStart);
const editHandler = viewSource.slice(handlerStart, handlerEnd);

assert.ok(editStart >= 0, "必须提供编辑长期记忆 API");
assert.match(
  editApi,
  /patch<void>\(`\/users\/memories\/\$\{memoryId\}`, request\)/,
  "编辑长期记忆 API 必须调用后端 PATCH 路由",
);
assert.match(
  viewSource,
  /import \{[\s\S]*updateUserMemory,[\s\S]*\} from "\.\.\/\.\.\/api\/api\.ts"/,
  "记忆管理页必须引入编辑长期记忆 API",
);
assert.match(
  viewSource,
  /<Modal[\s\S]*open=\{editingMemory !== null\}[\s\S]*onOk=\{handleEditMemory\}/,
  "编辑长期记忆必须使用受控编辑弹窗",
);
assert.match(
  editHandler,
  /await updateUserMemory\(editingMemory\.id, \{ content: editingContent \}\);[\s\S]*await refresh\(\);/,
  "编辑成功后必须刷新列表",
);
assert.match(
  viewSource,
  /onClick=\{\(\) => handleStartEditMemory\(memory\)\}/,
  "每条长期记忆必须提供编辑操作",
);
