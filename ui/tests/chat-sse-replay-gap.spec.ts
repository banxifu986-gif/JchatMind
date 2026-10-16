import { expect, test } from "@playwright/test";

const sessionId = "g5-replay-gap-session";
const session = {
  id: sessionId,
  userId: "7",
  agentId: "agent-1",
  title: "历史恢复验证",
};
const historyMessages = Array.from({ length: 70 }, (_, index) => ({
  id: `history-${index + 1}`,
  sessionId,
  role: index % 2 === 0 ? "user" : "assistant",
  content: `窗口外历史消息 ${index + 1}`,
}));
const liveMessage = {
  id: "live-during-recovery",
  sessionId,
  role: "assistant",
  content: "历史刷新期间到达的实时回答",
};
const postResetMessage = {
  id: "live-after-sequence-reset",
  sessionId,
  role: "assistant",
  content: "序号重置后的新回答",
};

function generatedContentEvent(message: typeof liveMessage) {
  return {
    type: "AI_GENERATED_CONTENT",
    payload: { message, statusText: "", done: true },
    metadata: { chatMessageId: message.id },
  };
}

test("replay-gap recovery keeps a live SSE message while full history refresh is pending", async ({ page }) => {
  test.setTimeout(30_000);
  let messageHistoryRequests = 0;
  let sseRequests = 0;
  let markRecoveryHistoryStarted = () => {};
  let releaseRecoveryHistory = () => {};
  const recoveryHistoryStarted = new Promise<void>((resolve) => {
    markRecoveryHistoryStarted = resolve;
  });
  const recoveryHistoryGate = new Promise<void>((resolve) => {
    releaseRecoveryHistory = resolve;
  });

  const response = (data: unknown) => ({
    code: 200,
    message: "ok",
    data,
  });

  await page.addInitScript(() => {
    window.localStorage.setItem("jchatmind.token", "g5-replay-gap-test-token");
  });

  await page.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== "http://127.0.0.1:5173"
      || (!url.pathname.startsWith("/mock-api/") && !url.pathname.startsWith("/mock-sse/"))) {
      await route.continue();
      return;
    }

    if (url.pathname === `/mock-sse/connect/${sessionId}`) {
      sseRequests += 1;
      if (sseRequests === 2) {
        expect(route.request().headers()["last-event-id"]).toBe("71");
        await route.fulfill({
          status: 200,
          contentType: "text/event-stream",
          body: `event: replay-gap\nid:1\ndata:history_window_exceeded\n\nevent: message\nid:2\ndata:${JSON.stringify(generatedContentEvent(postResetMessage))}\n\n`,
        });
        return;
      }
      if (sseRequests > 2) {
        await route.fulfill({ status: 200, contentType: "text/event-stream", body: "" });
        return;
      }

      await page.getByText(historyMessages.at(-1)!.content, { exact: true }).waitFor({ timeout: 10_000 });
      await route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: `event: replay-gap\nid:70\ndata:history_window_exceeded\n\nevent: message\nid:71\ndata:${JSON.stringify(generatedContentEvent(liveMessage))}\n\n`,
      });
      return;
    }

    if (url.pathname.endsWith("/users/whoami")) {
      await route.fulfill({
        json: response({
          userId: 7,
          account: "g5-replay-gap-user",
          username: "G5 test user",
          isAdmin: 0,
          token: "g5-replay-gap-test-token",
        }),
      });
      return;
    }

    if (url.pathname.endsWith("/agents")) {
      await route.fulfill({ json: response({ agents: [] }) });
      return;
    }

    if (url.pathname.endsWith("/chat-sessions")) {
      await route.fulfill({ json: response({ chatSessions: [session] }) });
      return;
    }

    if (url.pathname.endsWith(`/chat-sessions/${sessionId}`)) {
      await route.fulfill({ json: response({ chatSession: session }) });
      return;
    }

    if (url.pathname.endsWith("/knowledge-bases")) {
      await route.fulfill({ json: response({ knowledgeBases: [] }) });
      return;
    }

    if (url.pathname.endsWith(`/harness/pending/${sessionId}`)) {
      await route.fulfill({ json: response({ approvals: [] }) });
      return;
    }

    if (url.pathname.endsWith(`/chat-messages/session/${sessionId}`)) {
      messageHistoryRequests += 1;
      if (messageHistoryRequests === 2) {
        markRecoveryHistoryStarted();
        await recoveryHistoryGate;
      }
      await route.fulfill({ json: response({ chatMessages: historyMessages }) });
      return;
    }

    await route.fulfill({ json: response({}) });
  });

  await page.goto(`/chat/${sessionId}`);
  await expect.poll(() => messageHistoryRequests, { timeout: 10_000 }).toBe(2);
  await recoveryHistoryStarted;
  try {
    await expect(page.getByText(liveMessage.content)).toBeVisible();
  } finally {
    releaseRecoveryHistory();
  }

  await expect(page.getByText(historyMessages[0]!.content, { exact: true })).toBeVisible();
  await expect(page.getByText(historyMessages.at(-1)!.content, { exact: true })).toBeVisible();
  await expect(page.getByText(liveMessage.content)).toBeVisible();
  expect(messageHistoryRequests).toBe(2);
  await expect(page.getByText(postResetMessage.content)).toBeVisible({ timeout: 5_000 });
  expect(messageHistoryRequests).toBe(3);
});
