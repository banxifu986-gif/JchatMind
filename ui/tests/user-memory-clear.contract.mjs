import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const apiSource = readFileSync(new URL("../src/api/api.ts", import.meta.url), "utf8");
const viewSource = readFileSync(
  new URL("../src/components/views/UserMemoryView.tsx", import.meta.url),
  "utf8",
);
const clearStart = apiSource.indexOf("export async function clearUserMemories");
const clearEnd = apiSource.indexOf("// ========== Auth APIs", clearStart);
const clearApi = apiSource.slice(clearStart, clearEnd);
const handlerStart = viewSource.indexOf("const handleClearMemories");
const handlerEnd = viewSource.indexOf("return (", handlerStart);
const clearHandler = viewSource.slice(handlerStart, handlerEnd);

assert.ok(clearStart >= 0, "必须提供清空长期记忆 API");
assert.match(
  clearApi,
  /del<void>\("\/users\/memories"\)/,
  "清空长期记忆 API 必须调用后端 DELETE 路由",
);
assert.match(
  viewSource,
  /import \{[\s\S]*clearUserMemories,[\s\S]*\} from "\.\.\/\.\.\/api\/api\.ts"/,
  "记忆管理页必须引入清空长期记忆 API",
);
assert.match(
  clearHandler,
  /await clearUserMemories\(\);[\s\S]*await refresh\(\);/,
  "清空长期记忆成功后必须刷新列表",
);
assert.match(
  viewSource,
  /onConfirm=\{handleClearMemories\}/,
  "清空长期记忆必须经二次确认后执行",
);
