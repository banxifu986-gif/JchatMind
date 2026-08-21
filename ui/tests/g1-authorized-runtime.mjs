const apiBase = process.env.G1_AUTHORIZED_API_BASE_URL;

if (!apiBase) {
  throw new Error("G1_AUTHORIZED_API_BASE_URL is required");
}

const runId = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
const account = `g1authorized${runId}`.slice(0, 32);
const password = `G1-Authorized-${runId}`;
const markerA = `G1_AUTHORIZED_A_${runId}`;
const markerB = `G1_AUTHORIZED_B_${runId}`;

function markStep(step) {
  globalThis.g1AuthorizedRuntimeStep = step;
}

function fail(step, response) {
  throw new Error(`${step} failed with HTTP ${response.status}`);
}

async function request(path, options = {}) {
  const response = await fetch(`${apiBase}${path}`, options);
  if (!response.ok) {
    fail(path, response);
  }
  const payload = await response.json();
  if (payload.code !== 200) {
    throw new Error(`${path} returned API code ${payload.code}`);
  }
  return payload.data;
}

function authenticatedHeaders(token) {
  return { Authorization: `Bearer ${token}` };
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function poll(label, predicate, timeoutMilliseconds = 180_000) {
  const deadline = Date.now() + timeoutMilliseconds;
  while (Date.now() < deadline) {
    const result = await predicate();
    if (result) {
      return result;
    }
    await sleep(500);
  }
  throw new Error(`${label} timed out`);
}

async function uploadHtml(token, kbId, marker) {
  const formData = new FormData();
  formData.append("kbId", kbId);
  formData.append(
    "file",
    new Blob([
      `<html><body><h1>${marker}</h1><p>runtime fixture content</p><h2>Details</h2><p>${marker} details</p></body></html>`,
    ], { type: "text/html" }),
    `${marker}.html`,
  );
  const response = await fetch(`${apiBase}/documents/upload`, {
    method: "POST",
    headers: {
      ...authenticatedHeaders(token),
      "Idempotency-Key": crypto.randomUUID(),
    },
    body: formData,
  });
  if (!response.ok) {
    fail("upload", response);
  }
  const payload = await response.json();
  if (payload.code !== 200) {
    throw new Error(`upload returned API code ${payload.code}`);
  }
  return payload.data;
}

async function observeTaskSse(token, taskId) {
  const sseBase = apiBase.replace(/\/api\/?$/, "");
  const controller = new AbortController();
  const statuses = [];
  const completion = (async () => {
    const response = await fetch(`${sseBase}/sse/ingestion/${encodeURIComponent(taskId)}`, {
      headers: authenticatedHeaders(token),
      signal: controller.signal,
    });
    if (!response.ok || !response.body) {
      throw new Error("task SSE connection failed");
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      for (const frame of frames) {
        if (!/^event:\s*ingestion-progress$/m.test(frame)) {
          continue;
        }
        const data = frame.match(/^data:\s*(.+)$/m)?.[1];
        if (data) {
          statuses.push(JSON.parse(data).status);
        }
      }
      if (done) {
        return;
      }
    }
  })();
  return { statuses, controller, completion };
}

async function waitForTask(token, taskId, statuses) {
  const task = await poll("ingestion task", async () => {
    const data = await request(`/ingestion/tasks/${taskId}`, {
      headers: authenticatedHeaders(token),
    });
    return data.status === "SUCCEEDED" ? data : null;
  });
  await poll("SSE terminal event", () => statuses.includes("SUCCEEDED"));
  if (!statuses.includes("RUNNING")) {
    throw new Error("task SSE did not publish RUNNING");
  }
  return task;
}

async function main() {
  markStep("register");
  const registration = await request("/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ account, username: "g1-authorized", password }),
  });
  const token = registration.token;
  if (!token) {
    throw new Error("registration did not return a token");
  }

  markStep("create-kb-a");
  const kbA = await request("/knowledge-bases", {
    method: "POST",
    headers: { ...authenticatedHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({ name: `G1 Authorized A ${runId}` }),
  });
  markStep("create-kb-b");
  const kbB = await request("/knowledge-bases", {
    method: "POST",
    headers: { ...authenticatedHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({ name: `G1 Authorized B ${runId}` }),
  });

  markStep("upload-a");
  const uploadA = await uploadHtml(token, kbA.knowledgeBaseId, markerA);
  markStep("wait-task-a");
  const sseA = await observeTaskSse(token, uploadA.taskId);
  await waitForTask(token, uploadA.taskId, sseA.statuses);
  sseA.controller.abort();
  await sseA.completion.catch((error) => {
    if (error.name !== "AbortError") {
      throw error;
    }
  });

  markStep("upload-b");
  const uploadB = await uploadHtml(token, kbB.knowledgeBaseId, markerB);
  markStep("wait-task-b");
  const sseB = await observeTaskSse(token, uploadB.taskId);
  await waitForTask(token, uploadB.taskId, sseB.statuses);
  sseB.controller.abort();
  await sseB.completion.catch((error) => {
    if (error.name !== "AbortError") {
      throw error;
    }
  });

  markStep("create-agent");
  const agent = await request("/agents", {
    method: "POST",
    headers: { ...authenticatedHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({
      name: `G1 Authorized Agent ${runId}`,
      model: "deepseek-chat",
      systemPrompt: "For each factual request, call KnowledgeTool and answer only from its response.",
      allowedTools: [],
      allowedKbs: [kbA.knowledgeBaseId, kbB.knowledgeBaseId],
      chatOptions: { messageLength: 10 },
    }),
  });
  markStep("create-session");
  const session = await request("/chat-sessions", {
    method: "POST",
    headers: { ...authenticatedHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({
      agentId: agent.agentId,
      title: "G1 authorized model runtime",
      metadata: { retrievalContext: { kbId: kbA.knowledgeBaseId } },
    }),
  });

  markStep("submit-user-message");
  await request("/chat-messages", {
    method: "POST",
    headers: { ...authenticatedHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({
      agentId: agent.agentId,
      sessionId: session.chatSessionId,
      role: "user",
      content: `Find the exact knowledge-base marker ${markerA} and report it.`,
    }),
  });

  markStep("wait-model-tool-response");
  const messages = await poll("model-driven tool response", async () => {
    const data = await request(`/chat-messages/session/${session.chatSessionId}`, {
      headers: authenticatedHeaders(token),
    });
    const all = data.chatMessages ?? [];
    const tool = all.find((message) => String(message.role).toUpperCase() === "TOOL");
    const assistant = [...all].reverse().find((message) => {
      if (String(message.role).toUpperCase() !== "ASSISTANT") {
        return false;
      }
      if (!String(message.content ?? "").trim()) {
        return false;
      }
      return (message.metadata?.toolCalls ?? []).length === 0;
    });
    if (!tool || !assistant) {
      return null;
    }
    return { tool, assistant };
  });

  markStep("assert-knowledge-tool-scope");
  const toolHasA = String(messages.tool.content ?? "").includes(markerA);
  const toolHasB = String(messages.tool.content ?? "").includes(markerB);
  const toolHasHtmlPath = String(messages.tool.content ?? "").includes(`${markerA} > Details`);
  const assistantContent = String(messages.assistant.content ?? "");
  const assistantHasA = assistantContent.includes(markerA);
  const assistantHasB = assistantContent.includes(markerB);
  const assistantHasFixtureContent = assistantContent.includes("runtime fixture content");
  const assistantLength = assistantContent.length;
  const assistantToolNames = (messages.assistant.metadata?.toolCalls ?? [])
    .map((toolCall) => toolCall.name)
    .filter(Boolean)
    .sort();
  globalThis.g1AuthorizedRuntimeScope = {
    toolHasA,
    toolHasB,
    toolHasHtmlPath,
    assistantHasA,
    assistantHasB,
    assistantHasFixtureContent,
    assistantLengthBucket: assistantLength === 0 ? "empty" : assistantLength <= 80 ? "short" : "long",
    assistantToolNames,
  };
  if (!toolHasA || toolHasB || !toolHasHtmlPath) {
    throw new Error("KnowledgeTool scope assertion failed");
  }
  markStep("assert-model-answer-scope");
  if (!assistantHasA || assistantHasB) {
    throw new Error("model answer scope assertion failed");
  }

  console.log("G1_AUTHORIZED_RUNTIME=PASS");
}

await main();
