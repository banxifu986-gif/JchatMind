import { expect, test, type Page, type Route } from "@playwright/test";

interface Memory {
  id: string;
  memoryType: string;
  content: string;
  sessionId?: string;
  expiresAt?: string;
}

interface Candidate {
  id: string;
  memoryType: string;
  content: string;
  sessionId?: string;
  evidence?: string;
}

test("G3 browser journey confirms, edits, expires, and clears only the current user's memories", async ({ page }) => {
  const candidate: Candidate = {
    id: "candidate-1",
    memoryType: "PREFERENCE",
    content: "偏好 TypeScript 方案",
    sessionId: "session-1",
    evidence: "用户明确表达",
  };
  const memories: Memory[] = [];
  const candidates: Candidate[] = [candidate];
  const requests: { method: string; path: string; body?: unknown }[] = [];

  await stubUserMemoryApi(page, memories, candidates, requests);
  await page.addInitScript(() => {
    window.localStorage.setItem("jchatmind.token", "g3-browser-token");
  });

  await page.goto("/user-memory");

  const candidatePanel = page.locator(".ant-card").filter({ hasText: "待确认候选" }).first();
  await expect(page.getByText("g3-user", { exact: true })).toBeVisible();
  await expect(page.getByText(candidate.content, { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "确认保存" }).click();
  await expect(candidatePanel.getByText(candidate.content, { exact: true })).toHaveCount(0);
  await expect(page.getByText(candidate.content, { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "编辑" }).click();
  await page.locator(".ant-modal textarea").fill("偏好 Spring Boot 方案");
  await page.getByRole("button", { name: "保存" }).click();
  await expect(page.getByText("偏好 Spring Boot 方案", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "有效期" }).click();
  await page.locator(".ant-modal input[type='datetime-local']").fill("2030-01-02T03:04");
  await page.getByRole("button", { name: "保存" }).click();
  await expect(page.getByText("有效至 2030-01-02 03:04", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "清空" }).first().click();
  await page.getByRole("button", { name: "清空" }).last().click();
  await expect(page.getByText("暂无已保存记忆")).toBeVisible();

  expect(requests).toEqual(expect.arrayContaining([
    { method: "POST", path: "/api/users/memory-candidates/candidate-1/confirm" },
    { method: "PATCH", path: "/api/users/memories/memory-1", body: { content: "偏好 Spring Boot 方案" } },
    { method: "PATCH", path: "/api/users/memories/memory-1/expiration", body: { expiresAt: "2030-01-02T03:04" } },
    { method: "DELETE", path: "/api/users/memories" },
  ]));
});

async function stubUserMemoryApi(
  page: Page,
  memories: Memory[],
  candidates: Candidate[],
  requests: { method: string; path: string; body?: unknown }[],
) {
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    const body = request.postData() ? request.postDataJSON() : undefined;
    requests.push({ method, path, ...(body ? { body } : {}) });

    if (method === "GET" && path === "/api/users/whoami") {
      await respond(route, {
        userId: 1,
        account: "g3-account",
        username: "g3-user",
        isAdmin: 0,
      });
      return;
    }
    if (method === "GET" && path === "/api/chat-sessions") {
      await respond(route, { chatSessions: [] });
      return;
    }
    if (method === "GET" && path === "/api/users/memories") {
      await respond(route, { memories });
      return;
    }
    if (method === "GET" && path === "/api/users/memory-candidates") {
      await respond(route, { candidates });
      return;
    }
    if (method === "POST" && path === "/api/users/memory-candidates/candidate-1/confirm") {
      candidates.splice(0, candidates.length);
      memories.push({
        id: "memory-1",
        memoryType: candidate.memoryType,
        content: candidate.content,
        sessionId: candidate.sessionId,
        expiresAt: "2027-01-01T00:00:00",
      });
      await respond(route, null);
      return;
    }
    if (method === "PATCH" && path === "/api/users/memories/memory-1") {
      memories[0].content = String((body as { content: string }).content);
      await respond(route, null);
      return;
    }
    if (method === "PATCH" && path === "/api/users/memories/memory-1/expiration") {
      memories[0].expiresAt = String((body as { expiresAt: string }).expiresAt);
      await respond(route, null);
      return;
    }
    if (method === "DELETE" && path === "/api/users/memories") {
      memories.splice(0, memories.length);
      await respond(route, null);
      return;
    }
    await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ code: 404, message: "not found" }) });
  });
}

async function respond(route: Route, data: unknown) {
  await route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ code: 200, message: "success", data }),
  });
}
