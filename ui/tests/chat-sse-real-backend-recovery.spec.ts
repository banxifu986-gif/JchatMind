import { expect, test, type Response } from "@playwright/test";
import { writeFile } from "node:fs/promises";

test("replay-gap refreshes real backend history after a browser SSE reconnect", async ({ page }) => {
  const sessionId = process.env.G5_CHAT_SESSION_ID;
  const ownerToken = process.env.G5_CHAT_OWNER_TOKEN;
  const browserLoginPassword = process.env.G5_BROWSER_LOGIN_PASSWORD;
  const otherBrowserLoginPassword = process.env.G5_BROWSER_OTHER_LOGIN_PASSWORD;
  const otherKbId = process.env.G5_OTHER_KB_ID;
  const ownerMessageContent = process.env.G5_BROWSER_OWNER_MESSAGE_CONTENT;
  const cursorReadyFile = process.env.G5_BROWSER_CURSOR_READY_FILE;
  const directSse = process.env.G5_BROWSER_DIRECT_SSE === "true";
  const directHistory = process.env.G5_BROWSER_DIRECT_HISTORY === "true";
  const realSessions = process.env.G5_BROWSER_REAL_SESSIONS === "true";
  const realWhoami = process.env.G5_BROWSER_REAL_WHOAMI === "true";
  const realAgents = process.env.G5_BROWSER_REAL_AGENTS === "true";
  const realKbs = process.env.G5_BROWSER_REAL_KBS === "true";
  const realPending = process.env.G5_BROWSER_REAL_PENDING === "true";
  const realTools = process.env.G5_BROWSER_REAL_TOOLS === "true";
  const expectedKbId = process.env.G5_EXPECTED_KB_ID;
  const backendBaseUrl = process.env.G5_CHAT_HTTP_URL;
  test.skip(!sessionId || !ownerToken || !cursorReadyFile
    || ((directSse || directHistory) && !backendBaseUrl)
    || (realKbs && !expectedKbId)
    || (browserLoginPassword && (!otherBrowserLoginPassword || !otherKbId || !ownerMessageContent)),
    "Requires the isolated G5 HTTP fixture");
  test.setTimeout(45_000);

  const chatSessionId = sessionId!;
  const token = ownerToken!;
  const uiOrigin = new URL(process.env.G1_UI_BASE_URL ?? "http://127.0.0.1:5173").origin;
  const apiOrigin = directHistory ? new URL(backendBaseUrl!).origin : uiOrigin;
  const sseOrigin = directSse ? new URL(backendBaseUrl!).origin : uiOrigin;
  const apiPrefix = directHistory ? "/api/" : "/mock-api/";
  const historyPath = `${apiPrefix}chat-messages/session/${chatSessionId}`;
  const sessionListPath = `${apiPrefix}chat-sessions`;
  const sessionDetailPath = `${sessionListPath}/${chatSessionId}`;
  const whoamiPath = `${apiPrefix}users/whoami`;
  const agentsPath = `${apiPrefix}agents`;
  const kbsPath = `${apiPrefix}knowledge-bases`;
  const pendingPath = `${apiPrefix}harness/pending/${chatSessionId}`;
  const toolsPath = `${apiPrefix}tools`;
  const ssePath = directSse
    ? `/sse/connect/${chatSessionId}` : `/mock-sse/connect/${chatSessionId}`;
  const session = {
    id: chatSessionId,
    userId: "7",
    agentId: "agent-1",
    title: "真实历史恢复验证",
  };
  const historyRequests: Record<string, string>[] = [];
  const historyResponses: Response[] = [];
  const sessionResponses: Response[] = [];
  const whoamiResponses: Promise<{ status: number; body: {
    code: number;
    data: { userId: number; token: string };
  } }>[] = [];
  const agentResponses: Response[] = [];
  const kbResponses: Response[] = [];
  const pendingResponses: Response[] = [];
  const toolResponses: Response[] = [];
  const createMessageResponses: Response[] = [];
  const sseRequests: Record<string, string>[] = [];
  const mockedPaths: string[] = [];
  let recordPageRequests = !browserLoginPassword;
  let preloginKbRequests = 0;
  let kbRequestCount = 0;

  const response = (data: unknown) => ({ code: 200, message: "ok", data });
  const mockHeaders = {
    "Content-Type": "application/json",
    ...(directHistory ? {
      "Access-Control-Allow-Origin": uiOrigin,
      "Access-Control-Allow-Credentials": "true",
      "Access-Control-Allow-Headers": "Authorization,Content-Type",
      "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    } : {}),
  };
  if (!browserLoginPassword) {
    await page.addInitScript((jwt) => {
      window.localStorage.setItem("jchatmind.token", jwt);
    }, token);
  }

  page.on("request", (request) => {
    if (browserLoginPassword && !recordPageRequests
      && request.method() === "GET"
      && new URL(request.url()).pathname === kbsPath) {
      preloginKbRequests += 1;
    }
    if (!recordPageRequests) return;
    const url = new URL(request.url());
    if (request.method() === "GET" && url.origin === apiOrigin && url.pathname === kbsPath) {
      kbRequestCount += 1;
    }
    if (request.method() === "GET" && url.origin === apiOrigin && url.pathname === historyPath) {
      historyRequests.push(request.headers());
    }
    if (request.method() === "GET" && url.origin === sseOrigin && url.pathname === ssePath) {
      sseRequests.push(request.headers());
    }
  });
  page.on("response", (networkResponse) => {
    const url = new URL(networkResponse.url());
    if (!recordPageRequests) {
      if (realTools && networkResponse.request().method() === "GET"
        && url.origin === apiOrigin && url.pathname === toolsPath) {
        toolResponses.push(networkResponse);
      }
      return;
    }
    if (networkResponse.request().method() === "GET"
      && url.origin === apiOrigin && url.pathname === historyPath) {
      historyResponses.push(networkResponse);
    }
    if (realSessions && networkResponse.request().method() === "GET"
      && url.origin === apiOrigin
      && (url.pathname === sessionListPath || url.pathname === sessionDetailPath)) {
      sessionResponses.push(networkResponse);
    }
    if (realWhoami && networkResponse.request().method() === "GET"
      && url.origin === apiOrigin && url.pathname === whoamiPath) {
      whoamiResponses.push(networkResponse.json().then((body) => ({
        status: networkResponse.status(), body,
      })));
    }
    if (realAgents && networkResponse.request().method() === "GET"
      && url.origin === apiOrigin && url.pathname === agentsPath) {
      agentResponses.push(networkResponse);
    }
    if (realKbs && networkResponse.request().method() === "GET"
      && url.origin === apiOrigin && url.pathname === kbsPath) {
      kbResponses.push(networkResponse);
    }
    if (realPending && networkResponse.request().method() === "GET"
      && url.origin === apiOrigin && url.pathname === pendingPath) {
      pendingResponses.push(networkResponse);
    }
    if (realTools && networkResponse.request().method() === "GET"
      && url.origin === apiOrigin && url.pathname === toolsPath) {
      toolResponses.push(networkResponse);
    }
    if (networkResponse.request().method() === "POST"
      && url.origin === apiOrigin && url.pathname === `${apiPrefix}chat-messages`) {
      createMessageResponses.push(networkResponse);
    }
  });

  await page.route((url) => url.origin === apiOrigin
    && url.pathname.startsWith(apiPrefix) && url.pathname !== historyPath
    && (!realWhoami || url.pathname !== whoamiPath)
    && (!realAgents || url.pathname !== agentsPath)
    && (!realKbs || url.pathname !== kbsPath)
    && (!realPending || url.pathname !== pendingPath)
    && (!realTools || url.pathname !== toolsPath)
    && (!browserLoginPassword || url.pathname !== `${apiPrefix}users/login`)
    && (!browserLoginPassword || url.pathname !== `${apiPrefix}chat-messages`)
    && (!realSessions || (url.pathname !== sessionListPath
      && url.pathname !== sessionDetailPath)), async (route) => {
    const url = new URL(route.request().url());
    if (recordPageRequests) mockedPaths.push(`${route.request().method()} ${url.pathname}`);
    if (route.request().method() === "OPTIONS") {
      await route.fulfill({ status: 204, headers: mockHeaders });
      return;
    }

    if (url.pathname.endsWith("/users/whoami")) {
      await route.fulfill({
        headers: mockHeaders,
        json: response({
          userId: 7,
          account: "g5-http-user",
          username: "G5 HTTP user",
          isAdmin: 0,
          token,
        }),
      });
      return;
    }
    if (url.pathname.endsWith("/agents")) {
      await route.fulfill({ headers: mockHeaders, json: response({ agents: [] }) });
      return;
    }
    if (url.pathname.endsWith("/chat-sessions")) {
      await route.fulfill({ headers: mockHeaders, json: response({ chatSessions: [session] }) });
      return;
    }
    if (url.pathname.endsWith(`/chat-sessions/${chatSessionId}`)) {
      await route.fulfill({ headers: mockHeaders, json: response({ chatSession: session }) });
      return;
    }
    if (url.pathname.endsWith("/knowledge-bases")) {
      await route.fulfill({ headers: mockHeaders, json: response({ knowledgeBases: [] }) });
      return;
    }
    if (url.pathname.endsWith(`/harness/pending/${chatSessionId}`)) {
      await route.fulfill({ headers: mockHeaders, json: response({ approvals: [] }) });
      return;
    }
    await route.fulfill({ headers: mockHeaders, json: response({}) });
  });

  let activeToken = token;
  if (browserLoginPassword) {
    await page.goto("/");
    await page.waitForLoadState("networkidle");
    expect(preloginKbRequests).toBe(0);
    expect(await page.evaluate(() => window.localStorage.getItem("jchatmind.token"))).toBeNull();
    recordPageRequests = true;
    await page.locator(".app-sidebar__auth").getByRole("button", { name: "登录" }).click();
    const dialog = page.getByRole("dialog", { name: "登录" });
    await dialog.getByPlaceholder("账号").fill("g5-full-user");
    await dialog.getByPlaceholder("密码").fill(browserLoginPassword);
    const loginResponsePromise = page.waitForResponse((networkResponse) =>
      networkResponse.request().method() === "POST"
      && new URL(networkResponse.url()).pathname === `${apiPrefix}users/login`);
    await dialog.locator('button[type="submit"]').click();
    const loginResponse = await loginResponsePromise;
    expect(loginResponse.status()).toBe(200);
    const loginBody = await loginResponse.json();
    expect(loginBody.code).toBe(200);
    expect(loginBody.data.userId).toBe(7);
    activeToken = loginBody.data.token;
    expect(activeToken).toBeTruthy();
    await expect.poll(() => page.evaluate(() => window.localStorage.getItem("jchatmind.token")))
      .toBe(activeToken);
    await page.locator(".app-sidebar__session")
      .filter({ hasText: "G5 full application" }).click();
    await expect(page).toHaveURL(new RegExp(`/chat/${chatSessionId}$`));
  } else {
    await page.goto(`/chat/${chatSessionId}`);
  }
  await expect(page.getByText("history-69", { exact: true })).toBeVisible();
  await expect(page.getByText("g5-live-cursor", { exact: true }).first()).toBeVisible();
  await writeFile(cursorReadyFile!, "ready", { flag: "wx" });
  await expect.poll(() => historyRequests.length, { timeout: 15_000 }).toBe(2);
  await expect.poll(() => historyResponses.length, { timeout: 15_000 }).toBe(2);
  await expect.poll(() => sseRequests.length, { timeout: 15_000 }).toBeGreaterThanOrEqual(2);
  const acceptedTokens = new Set([activeToken]);
  if (realWhoami) {
    await expect.poll(() => whoamiResponses.length).toBeGreaterThanOrEqual(1);
    for (const { status, body } of await Promise.all(whoamiResponses)) {
      expect(status).toBe(200);
      expect(body.code).toBe(200);
      expect(body.data.userId).toBe(7);
      expect(typeof body.data.token).toBe("string");
      acceptedTokens.add(body.data.token);
    }
  }
  for (const headers of [...historyRequests, ...sseRequests]) {
    expect(acceptedTokens.has(headers.authorization?.replace(/^Bearer /, ""))).toBe(true);
  }
  expect(sseRequests[0]["last-event-id"]).toBeUndefined();
  expect(sseRequests[1]["last-event-id"]).toBe("71");
  for (const networkResponse of historyResponses) {
    expect(networkResponse.status()).toBe(200);
    const body = await networkResponse.json();
    expect(body.data.chatMessages).toHaveLength(70);
  }
  if (realSessions) {
    await expect.poll(() => sessionResponses.some((item) =>
      new URL(item.url()).pathname === sessionListPath)).toBe(true);
    await expect.poll(() => sessionResponses.some((item) =>
      new URL(item.url()).pathname === sessionDetailPath)).toBe(true);
    for (const networkResponse of sessionResponses) {
      expect(networkResponse.status()).toBe(200);
      const body = await networkResponse.json();
      expect(body.code).toBe(200);
      if (new URL(networkResponse.url()).pathname === sessionListPath) {
        expect(body.data.chatSessions.some((item: { id: string }) =>
          item.id === chatSessionId)).toBe(true);
      } else {
        expect(body.data.chatSession.id).toBe(chatSessionId);
      }
    }
  }
  if (realAgents) {
    await expect.poll(() => agentResponses.length).toBeGreaterThanOrEqual(1);
    const detailResponse = sessionResponses.find((item) =>
      new URL(item.url()).pathname === sessionDetailPath)!;
    const detailBody = await detailResponse.json();
    const expectedAgentId = detailBody.data.chatSession.agentId;
    for (const networkResponse of agentResponses) {
      expect(networkResponse.status()).toBe(200);
      const body = await networkResponse.json();
      expect(body.code).toBe(200);
      expect(body.data.agents.some((item: { id: string }) =>
        item.id === expectedAgentId)).toBe(true);
    }
  }
  if (realKbs) {
    await expect.poll(() => kbResponses.length).toBeGreaterThanOrEqual(1);
    for (const networkResponse of kbResponses) {
      expect(networkResponse.status()).toBe(200);
      const body = await networkResponse.json();
      expect(body.code).toBe(200);
      expect(body.data.knowledgeBases).toHaveLength(1);
      expect(body.data.knowledgeBases[0].id).toBe(expectedKbId);
    }
  }
  if (realPending) {
    await expect.poll(() => pendingResponses.length).toBeGreaterThanOrEqual(1);
    for (const networkResponse of pendingResponses) {
      expect(networkResponse.status()).toBe(200);
      const body = await networkResponse.json();
      expect(body.code).toBe(200);
      expect(body.data.approvals).toEqual([]);
    }
  }
  if (realTools) {
    await expect.poll(() => toolResponses.length).toBeGreaterThanOrEqual(1);
    for (const networkResponse of toolResponses) {
      expect(networkResponse.status()).toBe(200);
      const body = await networkResponse.json();
      expect(body.code).toBe(200);
      expect(Array.isArray(body.data)).toBe(true);
    }
  }
  await expect(page.getByText("history-0", { exact: true })).toBeVisible();
  await expect(page.getByText("history-69", { exact: true })).toBeVisible();
  if (directHistory && realWhoami && realSessions && realAgents && realKbs
    && realPending && realTools) {
    expect(mockedPaths).toEqual([]);
  }
  if (browserLoginPassword) {
    await page.locator('button[title="知识库"]').click();
    await expect(page.locator(".app-sidebar__knowledge-base")).toHaveCount(1);
    await expect(page.locator(".app-sidebar__knowledge-base")).toContainText("G5 owner KB");
    const requestsBeforeLogout = kbRequestCount;
    await page.locator(".app-sidebar__account").click();
    await page.getByRole("menuitem", { name: /退出登录/ }).click();
    await expect(page.locator(".app-sidebar__auth")).toBeVisible();
    await expect(page.locator(".app-sidebar__knowledge-base")).toHaveCount(0);
    await expect(page.locator(".app-sidebar__session")).toHaveCount(0);
    expect(await page.evaluate(() => window.localStorage.getItem("jchatmind.token"))).toBeNull();
    await page.waitForLoadState("networkidle");
    expect(kbRequestCount).toBe(requestsBeforeLogout);
    const secondKbResponsesStart = kbResponses.length;
    await page.locator(".app-sidebar__auth").getByRole("button", { name: "登录" }).click();
    const secondDialog = page.getByRole("dialog", { name: "登录" });
    await secondDialog.getByPlaceholder("账号").fill("g5-other-user");
    await secondDialog.getByPlaceholder("密码").fill(otherBrowserLoginPassword!);
    const secondLoginResponsePromise = page.waitForResponse((networkResponse) =>
      networkResponse.request().method() === "POST"
      && new URL(networkResponse.url()).pathname === `${apiPrefix}users/login`);
    await secondDialog.locator('button[type="submit"]').click();
    const secondLoginResponse = await secondLoginResponsePromise;
    expect(secondLoginResponse.status()).toBe(200);
    const secondLoginBody = await secondLoginResponse.json();
    expect(secondLoginBody.code).toBe(200);
    expect(secondLoginBody.data.userId).toBe(8);
    await expect.poll(() => page.evaluate(() => window.localStorage.getItem("jchatmind.token")))
      .toBe(secondLoginBody.data.token);
    await expect.poll(() => kbResponses.length).toBeGreaterThan(secondKbResponsesStart);
    for (const networkResponse of kbResponses.slice(secondKbResponsesStart)) {
      expect(networkResponse.status()).toBe(200);
      const body = await networkResponse.json();
      expect(body.code).toBe(200);
      expect(body.data.knowledgeBases).toHaveLength(1);
      expect(body.data.knowledgeBases[0].id).toBe(otherKbId);
    }
    await expect(page.locator(".app-sidebar__knowledge-base")).toHaveCount(1);
    await expect(page.locator(".app-sidebar__knowledge-base")).toContainText("G5 other KB");
    await expect(page.locator(".app-sidebar__knowledge-base")).not.toContainText("G5 owner KB");
    await expect(page.locator(".app-sidebar__session")).toHaveCount(0);
    expect(mockedPaths).toEqual([]);
    await page.waitForLoadState("networkidle");
    recordPageRequests = false;
    const forbiddenHistoryPromise = page.waitForResponse((networkResponse) =>
      networkResponse.request().method() === "GET"
      && new URL(networkResponse.url()).pathname === historyPath);
    const forbiddenSsePromise = page.waitForResponse((networkResponse) =>
      networkResponse.request().method() === "GET"
      && new URL(networkResponse.url()).pathname === ssePath);
    await page.goto(`/chat/${chatSessionId}`);
    const [forbiddenHistory, forbiddenSse] = await Promise.all([
      forbiddenHistoryPromise, forbiddenSsePromise,
    ]);
    expect(forbiddenHistory.status()).toBe(200);
    const forbiddenBody = await forbiddenHistory.json();
    expect(forbiddenBody.code).toBe(500);
    expect(forbiddenBody.data).toBeNull();
    expect(forbiddenSse.status()).toBe(404);
    await expect(page.getByText("history-0", { exact: true })).toHaveCount(0);
    await expect(page.getByText("history-69", { exact: true })).toHaveCount(0);

    await page.locator(".app-sidebar__account").click();
    await page.getByRole("menuitem", { name: /退出登录/ }).click();
    await expect(page.locator(".app-sidebar__auth")).toBeVisible();
    expect(await page.evaluate(() => window.localStorage.getItem("jchatmind.token"))).toBeNull();
    recordPageRequests = true;
    await page.locator(".app-sidebar__auth").getByRole("button", { name: "登录" }).click();
    const ownerDialog = page.getByRole("dialog", { name: "登录" });
    await ownerDialog.getByPlaceholder("账号").fill("g5-full-user");
    await ownerDialog.getByPlaceholder("密码").fill(browserLoginPassword!);
    const ownerLoginResponsePromise = page.waitForResponse((networkResponse) =>
      networkResponse.request().method() === "POST"
      && new URL(networkResponse.url()).pathname === `${apiPrefix}users/login`);
    await ownerDialog.locator('button[type="submit"]').click();
    const ownerLoginResponse = await ownerLoginResponsePromise;
    expect(ownerLoginResponse.status()).toBe(200);
    const ownerLoginBody = await ownerLoginResponse.json();
    expect(ownerLoginBody.code).toBe(200);
    expect(ownerLoginBody.data.userId).toBe(7);
    await expect.poll(() => page.evaluate(() => window.localStorage.getItem("jchatmind.token")))
      .toBe(ownerLoginBody.data.token);
    await page.locator('button[title="知识库"]').click();
    await expect(page).toHaveURL(/\/knowledge-base$/);
    const priorHistoryResponseCount = historyResponses.length;
    const priorSessionResponseCount = sessionResponses.length;
    await page.locator(".app-sidebar__session")
      .filter({ hasText: "G5 full application" }).click();
    await expect(page).toHaveURL(new RegExp(`/chat/${chatSessionId}$`));
    await expect.poll(() => historyResponses.length).toBeGreaterThan(priorHistoryResponseCount);
    await expect.poll(() => sessionResponses.length).toBeGreaterThan(priorSessionResponseCount);
    const ownerPageHistoryResponse = historyResponses.at(-1)!;
    expect(ownerPageHistoryResponse.status()).toBe(200);
    const ownerPageHistoryBody = await ownerPageHistoryResponse.json();
    expect(ownerPageHistoryBody.code).toBe(200);
    expect(ownerPageHistoryBody.data.chatMessages).toHaveLength(70);
    await expect(page.getByText("history-69", { exact: true })).toBeVisible();
    const createResponsePromise = page.waitForResponse((networkResponse) =>
      networkResponse.request().method() === "POST"
      && new URL(networkResponse.url()).pathname === `${apiPrefix}chat-messages`);
    const refreshedHistoryPromise = page.waitForResponse((networkResponse) =>
      networkResponse.request().method() === "GET"
      && new URL(networkResponse.url()).pathname === historyPath);
    const composer = page.getByPlaceholder("输入消息...");
    await composer.fill(ownerMessageContent!);
    await composer.press("Enter");
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(200);
    const createBody = await createResponse.json();
    expect(createBody.code).toBe(200);
    expect(createBody.data.chatMessageId).toBeTruthy();
    const refreshedHistory = await refreshedHistoryPromise;
    expect(refreshedHistory.status()).toBe(200);
    const refreshedBody = await refreshedHistory.json();
    expect(refreshedBody.code).toBe(200);
    expect(refreshedBody.data.chatMessages.some((item: { content: string }) =>
      item.content === ownerMessageContent)).toBe(true);
    await expect(page.getByText(ownerMessageContent!, { exact: true })).toBeVisible();
    await expect(page.getByText("G5 mocked agent reply", { exact: true })).toBeVisible();
    expect(createMessageResponses.some((responseItem) => responseItem === createResponse)).toBe(true);
    expect(mockedPaths).toEqual([]);
  }
});
