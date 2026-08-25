import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const apiSource = readFileSync(new URL("../src/api/api.ts", import.meta.url), "utf8");
const viewSource = readFileSync(
  new URL("../src/components/views/UserMemoryView.tsx", import.meta.url),
  "utf8",
);
const discardStart = apiSource.indexOf("export async function discardUserMemoryCandidate");
const discardEnd = apiSource.indexOf("export async function deleteUserMemory", discardStart);
const discardApi = apiSource.slice(discardStart, discardEnd);
const handlerStart = viewSource.indexOf("const handleDiscardCandidate");
const handlerEnd = viewSource.indexOf("const handleDeleteMemory", handlerStart);
const discardHandler = viewSource.slice(handlerStart, handlerEnd);

assert.ok(discardStart >= 0, "必须提供候选记忆忽略 API");
assert.match(
  discardApi,
  /post<void>\(`\/users\/memory-candidates\/\$\{candidateId\}\/discard`\)/,
  "候选记忆忽略 API 必须调用后端 discard 路由",
);
assert.match(
  viewSource,
  /import \{[\s\S]*discardUserMemoryCandidate,[\s\S]*\} from "\.\.\/\.\.\/api\/api\.ts"/,
  "记忆管理页必须引入候选记忆忽略 API",
);
assert.match(
  discardHandler,
  /await discardUserMemoryCandidate\(candidateId\);[\s\S]*await refresh\(\);/,
  "候选记忆忽略成功后必须刷新列表",
);
assert.match(
  viewSource,
  /onConfirm=\{\(\) => handleDiscardCandidate\(candidate\.id\)\}/,
  "候选记忆忽略必须经确认后执行",
);
