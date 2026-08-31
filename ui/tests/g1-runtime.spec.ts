import { test, expect, type APIRequestContext, type Page } from "@playwright/test";

const apiBase = process.env.G1_API_BASE_URL ?? "http://127.0.0.1:18080/api";
const runId = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
const accountA = `g1e2ea${runId}`.slice(0, 32);
const accountB = `g1e2eb${runId}`.slice(0, 32);
const password = `G1-E2E-${runId}`;
const documentName = `g1-browser-${runId}.pdf`;
const documentSecret = `G1_BROWSER_SECRET_${runId}`;

async function register(request: APIRequestContext, account: string, username: string) {
  const response = await request.post(`${apiBase}/users`, {
    data: { account, username, password },
  });
  expect(response.ok()).toBeTruthy();
}

async function login(page: Page, account: string) {
  const loginButton = page.locator("button").filter({ hasText: /登\s*录/ });
  await loginButton.first().click();
  await page.getByPlaceholder("账号").fill(account);
  await page.getByPlaceholder("密码").fill(password);
  await loginButton.last().click();
  await expect(page.getByRole("button", { name: /g1-user/ })).toBeVisible();
}

async function createKnowledgeBase(page: Page, name: string) {
  await page.getByRole("button", { name: "新建知识库" }).click();
  await page.getByPlaceholder("请输入知识库名称").fill(name);
  await page.locator("button").filter({ hasText: /创\s*建/ }).click();
  await expect(page.getByText(name, { exact: true })).toBeVisible();
  await page.getByText(name, { exact: true }).click();
  await expect(page).toHaveURL(/\/knowledge-base\//);
}

async function waitForTaskDeadLetter(page: Page) {
  await expect(page.getByText("重试已耗尽")).toBeVisible({ timeout: 90_000 });
}

async function observeIngestionSseStatuses(page: Page) {
  await page.addInitScript(() => {
    const observedWindow = window as typeof window & {
      __g1IngestionSseStatuses?: string[];
      __g1IngestionSseUrls?: string[];
      __g1IngestionSseStatusesByLabel?: Record<string, string[]>;
    };
    observedWindow.__g1IngestionSseStatuses = [];
    observedWindow.__g1IngestionSseUrls = [];
    observedWindow.__g1IngestionSseStatusesByLabel = {};
    const originalFetch = window.fetch;
    window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      const response = await originalFetch(input, init);
      const requestUrl = typeof input === "string"
        ? input
        : input instanceof URL
          ? input.href
          : input.url;
      if (!requestUrl.includes("/sse/ingestion/") || !response.body) {
        return response;
      }

      observedWindow.__g1IngestionSseUrls?.push(requestUrl);
      const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
      const observationLabel = headers.get("X-G1-Observe");
      if (observationLabel) {
        observedWindow.__g1IngestionSseStatusesByLabel![observationLabel] = [];
      }
      const [applicationStream, observerStream] = response.body.tee();
      void (async () => {
        const reader = observerStream.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (true) {
          const { value, done } = await reader.read();
          buffer += decoder.decode(value, { stream: !done });
          const frames = buffer.split(/\r?\n\r?\n/);
          buffer = frames.pop() ?? "";
          for (const frame of frames) {
            const eventName = frame.match(/^event:\s*(.+)$/m)?.[1]?.trim();
            const data = frame.match(/^data:\s*(.+)$/m)?.[1]?.trim();
            if (eventName === "ingestion-progress" && data) {
              const status = JSON.parse(data).status;
              if (typeof status === "string") {
                if (observationLabel) {
                  observedWindow.__g1IngestionSseStatusesByLabel?.[observationLabel]?.push(status);
                } else {
                  observedWindow.__g1IngestionSseStatuses?.push(status);
                }
              }
            }
          }
          if (done) {
            return;
          }
        }
      })();

      return new Response(applicationStream, {
        headers: response.headers,
        status: response.status,
        statusText: response.statusText,
      });
    };
  });
}

test("G1 browser journey keeps upload task and KB ownership scoped", async ({ page, request, browser }, testInfo) => {
  await register(request, accountA, "g1-user-a");
  await register(request, accountB, "g1-user-b");

  await page.goto("/knowledge-base");
  await login(page, accountA);
  await page.getByRole("tab", { name: "知识库" }).click();
  const kbAName = `G1 Browser A ${runId}`;
  await createKnowledgeBase(page, kbAName);
  const kbAUrl = page.url();

  const fileInput = page.locator('input[type="file"]');
  await fileInput.setInputFiles({
    name: documentName,
    mimeType: "application/pdf",
    buffer: Buffer.from(documentSecret),
  });
  await expect(page.getByText("文档已上传，正在处理")).toBeVisible();
  await waitForTaskDeadLetter(page);
  await expect(page.getByRole("button", { name: "重新处理" })).toBeVisible();
  await page.getByRole("button", { name: "重新处理" }).dblclick();
  await expect(page.getByText("任务状态已变更，请重试").last()).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("g1-upload-task-failure.png"), fullPage: true });

  const kbBName = `G1 Browser B ${runId}`;
  await page.getByRole("tab", { name: "知识库" }).click();
  await createKnowledgeBase(page, kbBName);
  await expect(page.getByText(documentName, { exact: true })).toHaveCount(0);
  await expect(page.getByText(documentSecret, { exact: true })).toHaveCount(0);

  const pageB = await browser.newPage();
  await pageB.goto("/knowledge-base");
  await login(pageB, accountB);
  await pageB.goto(kbAUrl);
  await expect(pageB.getByText("知识库不存在")).toBeVisible();
  await expect(pageB.getByText(documentName, { exact: true })).toHaveCount(0);
  await expect(pageB.getByText(documentSecret, { exact: true })).toHaveCount(0);
  await pageB.screenshot({ path: testInfo.outputPath("g1-cross-owner-denial.png"), fullPage: true });
  await pageB.close();
});

test("G1 browser journey exposes cancel failure feedback", async ({ page, request }) => {
  const accountC = `g1e2ec${runId}`.slice(0, 32);
  await register(request, accountC, "g1-user-c");
  await page.goto("/knowledge-base");
  await login(page, accountC);
  await page.getByRole("tab", { name: "知识库" }).click();
  await createKnowledgeBase(page, `G1 Browser Cancel ${runId}`);
  await page.locator('input[type="file"]').setInputFiles({
    name: `g1-cancel-${runId}.pdf`,
    mimeType: "application/pdf",
    buffer: Buffer.from("cancel path"),
  });
  await expect(page.getByText("文档已上传，正在处理")).toBeVisible();
  const cancelButton = page.getByRole("button", { name: "取消处理" });
  await expect(cancelButton).toBeVisible({ timeout: 5_000 });
  await cancelButton.dblclick();
  await expect(page.getByText("任务状态已变更，请重试").last()).toBeVisible();
});

test("G1 browser journey receives ingestion progress through authenticated SSE", async ({ page, request }) => {
  const accountD = `g1sse${runId}`.slice(0, 32);
  await register(request, accountD, "g1-user");
  await observeIngestionSseStatuses(page);
  await page.goto("/knowledge-base");
  await login(page, accountD);
  await page.getByRole("tab", { name: "知识库" }).click();
  await createKnowledgeBase(page, `G1 Browser SSE ${runId}`);

  const sseRequest = page.waitForRequest((candidate) => {
    const authorization = candidate.headers()["authorization"];
    return candidate.method() === "GET"
      && candidate.url().includes("/sse/ingestion/")
      && authorization?.startsWith("Bearer ");
  }, { timeout: 15_000 });

  await page.locator('input[type="file"]').setInputFiles({
    name: `g1-sse-${runId}.pdf`,
    mimeType: "application/pdf",
    buffer: Buffer.alloc(1),
  });

  await sseRequest;
  await expect.poll(
    () => page.evaluate(() => (window as typeof window & {
      __g1IngestionSseStatuses?: string[];
    }).__g1IngestionSseStatuses ?? []),
  ).toContain("RETRYING");
  await expect(page.getByText("等待重试")).toBeVisible({ timeout: 15_000 });
});

test("G1 browser journey receives retry progress published after its SSE starts", async ({ page, request }) => {
  const accountE = `g1sseok${runId}`.slice(0, 32);
  await register(request, accountE, "g1-user");
  await observeIngestionSseStatuses(page);
  await page.goto("/knowledge-base");
  await login(page, accountE);
  await page.getByRole("tab", { name: "知识库" }).click();
  await createKnowledgeBase(page, `G1 Browser SSE Success ${runId}`);

  const sseRequest = page.waitForRequest((candidate) => {
    const authorization = candidate.headers()["authorization"];
    return candidate.method() === "GET"
      && candidate.url().includes("/sse/ingestion/")
      && authorization?.startsWith("Bearer ");
  }, { timeout: 15_000 });
  await page.locator('input[type="file"]').setInputFiles({
    name: `g1-sse-retry-${runId}.pdf`,
    mimeType: "application/pdf",
    buffer: Buffer.alloc(1),
  });

  await sseRequest;
  await waitForTaskDeadLetter(page);
  const retryStreamConnected = await page.evaluate(async () => {
    const observedWindow = window as typeof window & {
      __g1IngestionSseUrls?: string[];
      __g1IngestionRetryStream?: Response;
    };
    const taskUrl = observedWindow.__g1IngestionSseUrls?.[0];
    const token = window.localStorage.getItem("jchatmind.token");
    if (!taskUrl || !token) {
      throw new Error("缺少任务 SSE 地址或登录凭据");
    }
    observedWindow.__g1IngestionRetryStream = await window.fetch(taskUrl, {
      headers: {
        Authorization: `Bearer ${token}`,
        "X-G1-Observe": "retry-stream",
      },
    });
    return observedWindow.__g1IngestionRetryStream.ok;
  });
  expect(retryStreamConnected).toBeTruthy();
  await expect.poll(
    () => page.evaluate(() => (window as typeof window & {
      __g1IngestionSseStatusesByLabel?: Record<string, string[]>;
    }).__g1IngestionSseStatusesByLabel?.["retry-stream"]?.[0]),
    { timeout: 15_000 },
  ).toBe("DEAD_LETTER");

  await page.getByRole("button", { name: "重新处理" }).click();
  await expect.poll(
    () => page.evaluate(() => (window as typeof window & {
      __g1IngestionSseStatusesByLabel?: Record<string, string[]>;
    }).__g1IngestionSseStatusesByLabel?.["retry-stream"] ?? []),
    { timeout: 15_000 },
  ).toContain("RUNNING");
});

test("G1 browser journey observes knowledge-base deletion completion", async ({ page, request }) => {
  const accountF = `g1delete${runId}`.slice(0, 32);
  const kbName = `G1 Browser Delete ${runId}`;
  await register(request, accountF, "g1-user-delete");
  await page.goto("/knowledge-base");
  await login(page, accountF);
  await page.getByRole("tab", { name: "知识库" }).click();
  await createKnowledgeBase(page, kbName);

  await page.getByRole("tab", { name: "知识库" }).click();
  await page.getByRole("button", { name: `删除知识库 ${kbName}` }).click();
  await expect(page.getByText("确定要删除这个知识库吗？")).toBeVisible();
  await page.getByRole("button", { name: /确\s*定/ }).last().click();

  await expect(page.getByText("知识库删除完成")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(kbName, { exact: true })).toHaveCount(0);
});
