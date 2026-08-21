import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const source = readFileSync(new URL("../src/api/api.ts", import.meta.url), "utf8");
const viewSource = readFileSync(
  new URL("../src/components/views/KnowledgeBaseView.tsx", import.meta.url),
  "utf8",
);
const uploadStart = source.indexOf("export async function uploadDocument");
const uploadEnd = source.indexOf("export async function deleteDocument", uploadStart);
const upload = source.slice(uploadStart, uploadEnd);

assert.ok(uploadStart >= 0, "必须保留文档上传 API");
assert.match(upload, /idempotencyKey: string/, "上传 API 必须接收调用方持有的幂等键");
assert.doesNotMatch(upload, /crypto\.randomUUID\(\)/, "上传 API 不得为每次重试重新生成幂等键");
assert.match(upload, /headers\["Idempotency-Key"\] = idempotencyKey/, "上传必须传递幂等键请求头");
assert.match(viewSource, /useRef<.*Map<string, string>/, "页面必须持有上传流程的幂等键");
assert.match(viewSource, /uploadDocument\(knowledgeBaseId, file as File, idempotencyKey\)/, "上传重试必须复用页面持有的幂等键");
assert.match(source, /taskId: string \| null/, "上传响应必须公开异步摄入任务 ID");
