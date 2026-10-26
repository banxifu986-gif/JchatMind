import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

test.use({ screenshot: "off", trace: "off", video: "off" });

interface ApiEnvelope<T> {
  code: number;
  data: T;
}

interface SessionResponse {
  chatSessionId: string;
}

interface CreateMessageResponse {
  chatMessageId: string;
}

interface AgentResponse {
  agentId: string;
}

interface AuthResponse {
  userId: number;
  token: string;
}

interface MemoryCandidate {
  id: string;
  sessionId: string;
  memoryType: string;
  content: string;
  evidence?: string;
}

interface Memory {
  id: string;
  memoryType: string;
  content: string;
}

interface ChatMessage {
  role: string;
  content: string;
  metadata?: { toolCalls?: unknown[] };
}

interface JourneyState {
  account: string;
  password: string;
  userId: number;
  agentId: string;
  preferenceCandidateId: string;
  preferenceMemoryId?: string;
}

const apiStatusCounts = new Map<number, number>();

test("live memory preference survives JVM restart and one-time instructions stay temporary", async ({ page, request }) => {
  test.skip(process.env.G3_MEMORY_LIVE !== "true", "Requires the opt-in isolated live harness");

  const apiBaseUrl = process.env.G3_API_BASE_URL;
  const statePath = process.env.G3_STATE_FILE;
  const phase = process.env.G3_MEMORY_PHASE;
  expect(apiBaseUrl).toBeTruthy();
  expect(statePath).toBeTruthy();
  expect(["prepare", "confirm", "resume"]).toContain(phase);

  if (phase === "prepare") {
    await preparePreference(page, request, apiBaseUrl!, statePath!);
    return;
  }

  if (phase === "confirm") {
    await confirmPreference(page, request, apiBaseUrl!, statePath!);
    return;
  }

  await verifyAfterRestart(page, request, apiBaseUrl!, statePath!);
});

async function preparePreference(
  page: Page,
  request: APIRequestContext,
  apiBaseUrl: string,
  statePath: string,
) {
  const account = `g3_${randomUUID().replaceAll("-", "").slice(0, 20)}`;
  const password = randomUUID() + randomUUID();
  const registration = await api<AuthResponse>(request, apiBaseUrl, "POST", "/users", undefined, {
    account,
    username: "G3 memory acceptance",
    password,
  });
  const userId = registration.userId;
  const agent = await api<AgentResponse>(request, apiBaseUrl, "POST", "/agents", registration.token, {
    name: "G3 memory acceptance agent",
    description: "Isolated preference persistence acceptance",
    systemPrompt: "Answer directly. Follow user preferences supplied in context. Do not use tools unless required.",
    model: "deepseek-chat",
    allowedTools: [],
    allowedKbs: [],
    chatOptions: { temperature: 0.1, topP: 1, messageLength: 256 },
  });
  const session = await createSession(request, apiBaseUrl, registration.token, agent.agentId);
  await authenticatePage(page, registration.token);
  await sendInLiveSession(request, page, apiBaseUrl, registration.token, session.chatSessionId, agent.agentId,
    "我长期偏好中文回答，请记住这个偏好。");

  const candidate = await waitForCandidate(request, apiBaseUrl, registration.token, session.chatSessionId);
  expect(candidate.memoryType).toBe("PREFERENCE");
  if (!candidate.evidence?.includes("我长期偏好中文回答")) {
    throw new Error("candidate evidence does not match the preference turn");
  }
  expect(candidate.sessionId).toBe(session.chatSessionId);
  expect(await getMemories(request, apiBaseUrl, registration.token)).toHaveLength(0);
  console.info("phase=prepare api=200 candidates=1 confirmedMemories=0");

  await page.goto("/user-memory");
  const candidateCard = page.locator(".app-memory-grid > .ant-card").first();
  await expect(candidateCard.getByText("PREFERENCE", { exact: true })).toBeVisible();
  await expect(candidateCard).toBeVisible();
  const state: JourneyState = {
    account,
    password,
    userId,
    agentId: agent.agentId,
    preferenceCandidateId: candidate.id,
  };
  writeFileSync(statePath, JSON.stringify(state), { encoding: "utf8", flag: "wx" });
}

async function confirmPreference(
  page: Page,
  request: APIRequestContext,
  apiBaseUrl: string,
  statePath: string,
) {
  const state = JSON.parse(readFileSync(statePath, "utf8")) as JourneyState;
  const login = await api<AuthResponse>(request, apiBaseUrl, "POST", "/users/login", undefined, {
    account: state.account,
    password: state.password,
  });
  const candidates = await getCandidates(request, apiBaseUrl, login.token);
  expect(candidates).toHaveLength(1);
  expect(candidates[0].id).toBe(state.preferenceCandidateId);
  expect(candidates[0].memoryType).toBe("PREFERENCE");
  expect(await getMemories(request, apiBaseUrl, login.token)).toHaveLength(0);
  await authenticatePage(page, login.token);
  await page.goto("/user-memory");
  const candidateCard = page.locator(".app-memory-grid > .ant-card").first();
  await expect(candidateCard.getByText("PREFERENCE", { exact: true })).toBeVisible();
  await candidateCard.getByRole("button", { name: "确认保存" }).click();

  const persisted = await waitForMemory(request, apiBaseUrl, login.token, candidates[0].content);
  expect(persisted.memoryType).toBe("PREFERENCE");
  expect(await getCandidates(request, apiBaseUrl, login.token)).toHaveLength(0);
  state.preferenceMemoryId = persisted.id;
  writeFileSync(statePath, JSON.stringify(state), { encoding: "utf8" });
  console.info("phase=confirmed api=200 candidates=0 confirmedMemories=1");
}

async function verifyAfterRestart(
  page: Page,
  request: APIRequestContext,
  apiBaseUrl: string,
  statePath: string,
) {
  const state = JSON.parse(readFileSync(statePath, "utf8")) as JourneyState;
  const login = await api<AuthResponse>(request, apiBaseUrl, "POST", "/users/login", undefined, {
    account: state.account,
    password: state.password,
  });
  expect(login.userId).toBe(state.userId);
  await authenticatePage(page, login.token);

  const memoriesAfterRestart = await getMemories(request, apiBaseUrl, login.token);
  expect(state.preferenceMemoryId).toBeTruthy();
  expect(memoriesAfterRestart).toHaveLength(1);
  expect(memoriesAfterRestart[0].id).toBe(state.preferenceMemoryId);
  expect(memoriesAfterRestart[0].memoryType).toBe("PREFERENCE");
  if (!/中文|Chinese/i.test(memoriesAfterRestart[0].content)) {
    throw new Error("confirmed preference was not recalled after the JVM restart");
  }
  expect(await getCandidates(request, apiBaseUrl, login.token)).toHaveLength(0);
  console.info("phase=restart api=200 candidates=0 confirmedMemories=1");

  const recallSession = await createSession(request, apiBaseUrl, login.token, state.agentId);
  const chineseReply = await sendInLiveSession(
    request, page, apiBaseUrl, login.token, recallSession.chatSessionId, state.agentId,
    "Please briefly describe what you can help me with.",
  );
  if (!/\p{Script=Han}/u.test(chineseReply)) {
    throw new Error("neutral English question did not receive a Chinese reply after restart");
  }
  await expectNoNewMemory(request, apiBaseUrl, login.token, 1);
  console.info("phase=recall api=200 candidates=0 confirmedMemories=1");

  const temporarySession = await createSession(request, apiBaseUrl, login.token, state.agentId);
  const englishReply = await sendInLiveSession(
    request,
    page,
    apiBaseUrl,
    login.token,
    temporarySession.chatSessionId,
    state.agentId,
    "For this reply only, answer in English: what is 2+2? This is a one-time request.",
  );
  if (!/4/.test(englishReply) || /\p{Script=Han}/u.test(englishReply)) {
    throw new Error("one-time English reply did not stay in English or missed the answer");
  }
  await expectNoNewMemory(request, apiBaseUrl, login.token, 1);
  console.info("phase=temporary api=200 candidates=0 confirmedMemories=1");

  const normalSession = await createSession(request, apiBaseUrl, login.token, state.agentId);
  const normalReply = await sendInLiveSession(
    request, page, apiBaseUrl, login.token, normalSession.chatSessionId, state.agentId,
    "Please briefly describe one thing you can help me with.",
  );
  if (!/\p{Script=Han}/u.test(normalReply)) {
    throw new Error("Chinese preference did not resume in a later normal session");
  }
  await expectNoNewMemory(request, apiBaseUrl, login.token, 1);
  console.info("phase=normal-followup api=200 candidates=0 confirmedMemories=1");
  console.info(`api-status-counts=${JSON.stringify(Object.fromEntries(apiStatusCounts))}`);
}

async function authenticatePage(page: Page, token: string) {
  await page.addInitScript((accessToken: string) => {
    window.localStorage.setItem("jchatmind.token", accessToken);
  }, token);
}

async function sendInLiveSession(
  request: APIRequestContext,
  page: Page,
  apiBaseUrl: string,
  token: string,
  sessionId: string,
  agentId: string,
  message: string,
): Promise<string> {
  await page.goto(`/chat/${sessionId}`);
  await expect(page.locator(".app-chat__active-agent-name")).toHaveText("G3 memory acceptance agent");
  const created = await api<CreateMessageResponse>(request, apiBaseUrl, "POST", "/chat-messages", token, {
    agentId,
    sessionId,
    role: "user",
    content: message,
  });
  expect(created.chatMessageId).toBeTruthy();
  const messages = await api<{ chatMessages: ChatMessage[] }>(
    request, apiBaseUrl, "GET", `/chat-messages/session/${sessionId}`, token,
  );
  expect(messages.chatMessages.some((item) => item.role.toLowerCase() === "user")).toBe(true);
  return waitForAssistantMessage(request, page, sessionId);
}

async function waitForAssistantMessage(
  request: APIRequestContext,
  page: Page,
  sessionId: string,
): Promise<string> {
  const apiBaseUrl = process.env.G3_API_BASE_URL!;
  const token = await page.evaluate(() => window.localStorage.getItem("jchatmind.token"));
  expect(token).toBeTruthy();
  let answer = "";
  await expect.poll(async () => {
    const messages = await api<{ chatMessages: ChatMessage[] }>(
      request,
      apiBaseUrl,
      "GET",
      `/chat-messages/session/${sessionId}`,
      token!,
    );
    answer = [...messages.chatMessages]
      .reverse()
      .find((message) => message.role.toLowerCase() === "assistant" && !message.metadata?.toolCalls?.length)
      ?.content ?? "";
    return answer.length;
  }, { timeout: 120_000, intervals: [500, 1000, 2000] }).toBeGreaterThan(0);
  return answer;
}

async function createSession(
  request: APIRequestContext,
  apiBaseUrl: string,
  token: string,
  agentId: string,
) {
  return api<SessionResponse>(request, apiBaseUrl, "POST", "/chat-sessions", token, {
    agentId,
    title: "G3 memory acceptance",
  });
}

async function waitForCandidate(
  request: APIRequestContext,
  apiBaseUrl: string,
  token: string,
  sessionId: string,
): Promise<MemoryCandidate> {
  let candidate: MemoryCandidate | undefined;
  await expect.poll(async () => {
    const result = await api<{ candidates: MemoryCandidate[] }>(
      request, apiBaseUrl, "GET", "/users/memory-candidates", token,
    );
    candidate = result.candidates.find((item) => item.sessionId === sessionId);
    return candidate?.id ?? "";
  }, { timeout: 60_000, intervals: [500, 1000, 2000] }).not.toBe("");
  return candidate!;
}

async function waitForMemory(
  request: APIRequestContext,
  apiBaseUrl: string,
  token: string,
  content: string,
): Promise<Memory> {
  let memory: Memory | undefined;
  await expect.poll(async () => {
    const result = await getMemories(request, apiBaseUrl, token);
    memory = result.find((item) => item.content === content);
    return memory?.id ?? "";
  }, { timeout: 30_000, intervals: [250, 500, 1000] }).not.toBe("");
  return memory!;
}

async function expectNoNewMemory(
  request: APIRequestContext,
  apiBaseUrl: string,
  token: string,
  expectedMemoryCount: number,
) {
  await new Promise((resolve) => setTimeout(resolve, 5_000));
  await expect.poll(async () => {
    const [memories, candidates] = await Promise.all([
      getMemories(request, apiBaseUrl, token),
      getCandidates(request, apiBaseUrl, token),
    ]);
    return `${memories.length}:${candidates.length}`;
  }, { timeout: 30_000, intervals: [1000, 2000] }).toBe(`${expectedMemoryCount}:0`);
}

async function getMemories(request: APIRequestContext, apiBaseUrl: string, token: string) {
  return (await api<{ memories: Memory[] }>(request, apiBaseUrl, "GET", "/users/memories", token)).memories;
}

async function getCandidates(request: APIRequestContext, apiBaseUrl: string, token: string) {
  return (await api<{ candidates: MemoryCandidate[] }>(
    request, apiBaseUrl, "GET", "/users/memory-candidates", token,
  )).candidates;
}

async function api<T>(
  request: APIRequestContext,
  apiBaseUrl: string,
  method: string,
  path: string,
  token?: string,
  body?: unknown,
): Promise<T> {
  const response = await request.fetch(`${apiBaseUrl}${path}`, {
    method,
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    data: body,
    timeout: 30_000,
  });
  apiStatusCounts.set(response.status(), (apiStatusCounts.get(response.status()) ?? 0) + 1);
  expect(response.status()).toBe(200);
  const envelope = await response.json() as ApiEnvelope<T>;
  expect(envelope.code).toBe(200);
  return envelope.data;
}
