import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const apiSource = readFileSync(new URL("../src/api/api.ts", import.meta.url), "utf8");
const viewSource = readFileSync(
  new URL("../src/components/views/KnowledgeBaseView.tsx", import.meta.url),
  "utf8",
);

assert.match(apiSource, /export async function getIngestionTask/, "必须提供任务查询 API");
assert.match(apiSource, /`\/ingestion\/tasks\/\$\{taskId\}`/, "任务查询必须使用受控任务路由");
assert.match(apiSource, /export async function subscribeIngestionTaskProgress/, "必须提供任务 SSE 订阅 API");
assert.ok(
  apiSource.includes('`${BASE_URL.replace(/\\/api\\/?$/, "")}/sse`'),
  "未配置 SSE 地址时必须回退到后端 /sse 路由",
);
assert.match(apiSource, /Authorization.*Bearer/, "任务 SSE 必须携带当前用户 JWT");
assert.match(apiSource, /headers\["Last-Event-ID"\] = String\(lastEventId\)/, "任务 SSE 重连必须携带最后事件序号");
assert.match(apiSource, /ReadableStream|response\.body\.getReader/, "任务 SSE 必须消费响应流");
assert.match(apiSource, /export async function cancelIngestionTask/, "必须提供任务取消 API");
assert.match(apiSource, /export async function retryIngestionTask/, "必须提供任务重试 API");
assert.match(viewSource, /window\.setInterval/, "知识库页面必须轮询非终态摄入任务");
assert.match(viewSource, /subscribeIngestionTaskProgress/, "知识库页面必须订阅任务 SSE 进度");
assert.match(viewSource, /new AbortController\(\)/, "任务 SSE 必须可在页面生命周期结束时取消");
assert.match(viewSource, /event\.sequence <= lastEventId/, "任务 SSE 必须忽略重复或倒序事件");
assert.match(viewSource, /accept="\.md,\.markdown,\.txt,\.html,\.pdf"/, "上传控件必须与后端支持的文件类型一致");
assert.match(viewSource, /cancelIngestionTask/, "知识库页面必须提供任务取消操作");
assert.match(viewSource, /retryIngestionTask/, "知识库页面必须提供任务重试操作");
assert.match(
  viewSource,
  /currentTask\?\.kbId === knowledgeBaseId \? currentTask : null/,
  "切换知识库后必须清除不属于当前知识库的摄入任务",
);
assert.match(
  viewSource,
  /nextTask\.kbId !== knowledgeBaseId/,
  "轮询返回的任务必须校验当前知识库范围",
);
assert.match(
  viewSource,
  /const CANCELLABLE_INGESTION_STATUSES = new Set\(\["QUEUED", "RETRYING"\]\)/,
  "运行中的任务不得显示取消操作",
);
assert.match(
  viewSource,
  /CANCELLABLE_INGESTION_STATUSES\.has\(currentIngestionTask\.status\)/,
  "取消按钮必须使用可取消状态范围",
);

const cancelHandler = viewSource.slice(
  viewSource.indexOf("const handleCancelIngestion"),
  viewSource.indexOf("const handleRetryIngestion"),
);
const retryHandler = viewSource.slice(
  viewSource.indexOf("const handleRetryIngestion"),
  viewSource.indexOf("// 格式化文件大小"),
);
assert.match(cancelHandler, /try\s*\{[\s\S]*await cancelIngestionTask[\s\S]*\}\s*catch \(error\)/, "取消失败必须被处理");
assert.match(retryHandler, /try\s*\{[\s\S]*await retryIngestionTask[\s\S]*\}\s*catch \(error\)/, "重试失败必须被处理");
assert.match(cancelHandler, /message\.error/, "取消失败必须反馈给用户");
assert.match(retryHandler, /message\.error/, "重试失败必须反馈给用户");
