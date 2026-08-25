import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const apiSource = readFileSync(new URL("../src/api/api.ts", import.meta.url), "utf8");
const viewSource = readFileSync(
  new URL("../src/components/views/UserMemoryView.tsx", import.meta.url),
  "utf8",
);

assert.match(
  apiSource,
  /expiresAt\?: string/,
  "长期记忆 API 类型必须暴露可空的到期时间，以兼容历史记录",
);
assert.match(
  apiSource,
  /export interface UpdateUserMemoryExpirationRequest\s*\{\s*expiresAt: string;/,
  "更新记忆期限必须要求提供明确的到期时间",
);
assert.doesNotMatch(
  apiSource,
  /expiresAt: string \| null/,
  "更新记忆期限不能再接受永久有效的空时间",
);
assert.match(
  apiSource,
  /export async function updateUserMemoryExpiration[\s\S]*patch<void>\(`\/users\/memories\/\$\{memoryId\}\/expiration`, request\)/,
  "记忆到期时间必须走独立的本人 PATCH 路由",
);
assert.match(
  viewSource,
  /updateUserMemoryExpiration/,
  "记忆管理页必须调用到期时间更新 API",
);
assert.match(
  viewSource,
  /有效期/,
  "记忆管理页必须显示并允许编辑有效期",
);
assert.match(
  viewSource,
  /默认有效期为 365 天/,
  "记忆管理页必须说明统一的 365 天默认有效期",
);
assert.doesNotMatch(
  viewSource,
  /永久/,
  "记忆管理页不能再提供永久有效状态",
);
assert.doesNotMatch(
  viewSource,
  /editingExpiration \|\| null/,
  "记忆管理页不能提交空期限来绕开默认有效期",
);
