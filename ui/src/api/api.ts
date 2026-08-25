import { get, post, patch, del, BASE_URL } from "./http.ts";
import type { ChatMessageVO, MessageType } from "../types";

export interface ChatOptions {
  temperature?: number;
  topP?: number;
  messageLength?: number;
}

export type ModelType = "deepseek-chat" | "glm-4.6";

export interface CreateAgentRequest {
  name: string;
  description?: string;
  systemPrompt?: string;
  model: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
}

export interface UpdateAgentRequest {
  name?: string;
  description?: string;
  systemPrompt?: string;
  model?: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
}

export interface CreateAgentResponse {
  agentId: string;
}

export interface AgentVO {
  id: string;
  name: string;
  description?: string;
  systemPrompt?: string;
  model: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
  createdAt?: string;
  updatedAt?: string;
}

export interface GetAgentsResponse {
  agents: AgentVO[];
}

export async function getAgents(): Promise<GetAgentsResponse> {
  return get<GetAgentsResponse>("/agents");
}

export async function createAgent(
  request: CreateAgentRequest,
): Promise<CreateAgentResponse> {
  return post<CreateAgentResponse>("/agents", request);
}

export async function deleteAgent(agentId: string): Promise<void> {
  return del<void>(`/agents/${agentId}`);
}

export async function updateAgent(
  agentId: string,
  request: UpdateAgentRequest,
): Promise<void> {
  return patch<void>(`/agents/${agentId}`, request);
}

export interface CreateChatSessionRequest {
  agentId: string;
  title?: string;
  metadata?: ChatSessionMetadata;
}

export interface CreateChatSessionResponse {
  chatSessionId: string;
}

export async function createChatSession(
  request: CreateChatSessionRequest,
): Promise<CreateChatSessionResponse> {
  return post<CreateChatSessionResponse>("/chat-sessions", request);
}

export interface ChatSessionVO {
  id: string;
  userId: string;
  agentId: string;
  title?: string;
  metadata?: ChatSessionMetadata;
}

export interface GetChatSessionsResponse {
  chatSessions: ChatSessionVO[];
}

export interface GetChatSessionResponse {
  chatSession: ChatSessionVO;
}

export interface UpdateChatSessionRequest {
  title?: string;
  metadata?: ChatSessionMetadata;
}

export interface RagRetrievalContext {
  sourceType?: string;
  sourceName?: string;
  contentPath?: string;
}

export interface ChatSessionMetadata {
  retrievalContext?: RagRetrievalContext;
}

export async function getChatSessions(): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>("/chat-sessions");
}

export async function getChatSession(
  chatSessionId: string,
): Promise<GetChatSessionResponse> {
  return get<GetChatSessionResponse>(`/chat-sessions/${chatSessionId}`);
}

export async function getChatSessionsByAgentId(
  agentId: string,
): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>(`/chat-sessions/agent/${agentId}`);
}

export async function updateChatSession(
  chatSessionId: string,
  request: UpdateChatSessionRequest,
): Promise<void> {
  return patch<void>(`/chat-sessions/${chatSessionId}`, request);
}

export async function deleteChatSession(
  chatSessionId: string,
): Promise<void> {
  return del<void>(`/chat-sessions/${chatSessionId}`);
}

export interface MetaData {
  [key: string]: unknown;
}

export interface GetChatMessagesResponse {
  chatMessages: ChatMessageVO[];
}

export interface CreateChatMessageRequest {
  agentId: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: MetaData;
}

export interface CreateChatMessageResponse {
  chatMessageId: string;
}

export interface UpdateChatMessageRequest {
  content?: string;
  metadata?: MetaData;
}

export async function getChatMessagesBySessionId(
  sessionId: string,
): Promise<GetChatMessagesResponse> {
  return get<GetChatMessagesResponse>(`/chat-messages/session/${sessionId}`);
}

export async function createChatMessage(
  request: CreateChatMessageRequest,
): Promise<CreateChatMessageResponse> {
  return post<CreateChatMessageResponse>("/chat-messages", request);
}

export async function updateChatMessage(
  chatMessageId: string,
  request: UpdateChatMessageRequest,
): Promise<void> {
  return patch<void>(`/chat-messages/${chatMessageId}`, request);
}

export async function deleteChatMessage(
  chatMessageId: string,
): Promise<void> {
  return del<void>(`/chat-messages/${chatMessageId}`);
}

export interface KnowledgeBaseVO {
  id: string;
  name: string;
  description?: string;
}

export interface CreateKnowledgeBaseRequest {
  name: string;
  description?: string;
}

export interface UpdateKnowledgeBaseRequest {
  name?: string;
  description?: string;
}

export interface GetKnowledgeBasesResponse {
  knowledgeBases: KnowledgeBaseVO[];
}

export interface CreateKnowledgeBaseResponse {
  knowledgeBaseId: string;
}

export interface DeleteKnowledgeBaseResponse {
  deletionTaskId: string;
}

export interface GetKnowledgeBaseDeletionTaskResponse {
  deletionTaskId: string;
  status: string;
  progress: number;
  attemptCount: number;
  maxAttempts: number;
  errorSummary?: string;
  createdAt: string;
  completedAt?: string;
}

export async function getKnowledgeBases(): Promise<GetKnowledgeBasesResponse> {
  return get<GetKnowledgeBasesResponse>("/knowledge-bases");
}

export async function createKnowledgeBase(
  request: CreateKnowledgeBaseRequest,
): Promise<CreateKnowledgeBaseResponse> {
  return post<CreateKnowledgeBaseResponse>("/knowledge-bases", request);
}

export async function deleteKnowledgeBase(
  knowledgeBaseId: string,
): Promise<DeleteKnowledgeBaseResponse> {
  return del<DeleteKnowledgeBaseResponse>(`/knowledge-bases/${knowledgeBaseId}`);
}

export async function getKnowledgeBaseDeletionTask(
  deletionTaskId: string,
): Promise<GetKnowledgeBaseDeletionTaskResponse> {
  return get<GetKnowledgeBaseDeletionTaskResponse>(`/knowledge-base-deletion-tasks/${deletionTaskId}`);
}

export async function updateKnowledgeBase(
  knowledgeBaseId: string,
  request: UpdateKnowledgeBaseRequest,
): Promise<void> {
  return patch<void>(`/knowledge-bases/${knowledgeBaseId}`, request);
}

export interface DocumentVO {
  id: string;
  kbId: string;
  filename: string;
  filetype: string;
  size: number;
}

export interface GetDocumentsResponse {
  documents: DocumentVO[];
}

export interface CreateDocumentResponse {
  documentId: string;
  taskId: string | null;
}

export async function getDocumentsByKbId(
  kbId: string,
): Promise<GetDocumentsResponse> {
  return get<GetDocumentsResponse>(`/documents/kb/${kbId}`);
}

export async function uploadDocument(
  kbId: string,
  file: File,
  idempotencyKey: string,
): Promise<CreateDocumentResponse> {
  const token = window.localStorage.getItem("jchatmind.token");
  const headers: Record<string, string> = {};
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }
  headers["Idempotency-Key"] = idempotencyKey;

  const formData = new FormData();
  formData.append("kbId", kbId);
  formData.append("file", file);

  const response = await fetch(`${BASE_URL}/documents/upload`, {
    method: "POST",
    headers,
    body: formData,
  });

  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`);
  }

  const apiResponse = await response.json();
  if (apiResponse.code !== 200) {
    throw new Error(apiResponse.message || "上传失败");
  }

  return apiResponse.data;
}

export type IngestionTaskStatus =
  | "QUEUED"
  | "RUNNING"
  | "RETRYING"
  | "FAILED"
  | "DEAD_LETTER"
  | "CANCELLED"
  | "SUCCEEDED";

export interface IngestionTaskVO {
  taskId: string;
  kbId: string;
  documentId: string;
  taskType: string;
  status: IngestionTaskStatus;
  attemptCount: number | null;
  maxAttempts: number | null;
  errorSummary: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export type IngestionTaskProgressEvent = IngestionTaskVO & {
  sequence?: number;
};

const INGESTION_SSE_BASE_URL = import.meta.env.VITE_SSE_BASE_URL
  || `${BASE_URL.replace(/\/api\/?$/, "")}/sse`;

export async function subscribeIngestionTaskProgress(
  taskId: string,
  onEvent: (event: IngestionTaskProgressEvent) => void,
  signal: AbortSignal,
  lastEventId?: number,
): Promise<void> {
  const token = window.localStorage.getItem("jchatmind.token");
  const headers: Record<string, string> = {};
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (lastEventId !== undefined) {
    headers["Last-Event-ID"] = String(lastEventId);
  }

  const response = await fetch(
    `${INGESTION_SSE_BASE_URL}/ingestion/${encodeURIComponent(taskId)}`,
    { headers, signal },
  );
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`);
  }
  if (!response.body) {
    throw new Error("摄入进度流不可用");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const consumeFrame = (frame: string) => {
    let eventName = "message";
    let eventId: number | undefined;
    const dataLines: string[] = [];
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith("event:")) {
        eventName = line.slice("event:".length).trim();
      } else if (line.startsWith("id:")) {
        const parsedId = Number(line.slice("id:".length).trim());
        if (Number.isFinite(parsedId)) {
          eventId = parsedId;
        }
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice("data:".length).trim());
      }
    }
    if (eventName !== "ingestion-progress" || dataLines.length === 0) {
      return;
    }
    const event = JSON.parse(dataLines.join("\n")) as IngestionTaskProgressEvent;
    if (event.sequence === undefined && eventId !== undefined) {
      event.sequence = eventId;
    }
    onEvent(event);
  };

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() ?? "";
    frames.filter((frame) => frame.trim()).forEach(consumeFrame);
    if (done) {
      if (buffer.trim()) {
        consumeFrame(buffer);
      }
      return;
    }
  }
}

export async function getIngestionTask(
  taskId: string,
): Promise<IngestionTaskVO> {
  return get<IngestionTaskVO>(`/ingestion/tasks/${taskId}`);
}

export async function cancelIngestionTask(taskId: string): Promise<void> {
  return post<void>(`/ingestion/tasks/${taskId}/cancel`);
}

export async function retryIngestionTask(taskId: string): Promise<void> {
  return post<void>(`/ingestion/tasks/${taskId}/retry`);
}

export async function deleteDocument(documentId: string): Promise<void> {
  return del<void>(`/documents/${documentId}`);
}

export interface PendingApprovalVO {
  id: string;
  sessionId: string;
  toolName: string;
  toolInput: string;
  callCount: number;
  status: string;
  createdAt: number;
  expiresAt: number;
}

export interface GetPendingApprovalsResponse {
  approvals: PendingApprovalVO[];
}

export async function getPendingApprovals(
  sessionId: string,
): Promise<GetPendingApprovalsResponse> {
  return get<GetPendingApprovalsResponse>(`/harness/pending/${sessionId}`);
}

export async function approveHarnessRequest(requestId: string): Promise<void> {
  return post<void>(`/harness/approve/${requestId}`);
}

export async function rejectHarnessRequest(requestId: string): Promise<void> {
  return post<void>(`/harness/reject/${requestId}`);
}

export type ToolType = "FIXED" | "OPTIONAL";

export interface ToolVO {
  name: string;
  description: string;
  type: ToolType;
}

export interface GetOptionalToolsResponse {
  tools: ToolVO[];
}

export async function getOptionalTools(): Promise<GetOptionalToolsResponse> {
  const tools = await get<ToolVO[]>("/tools");
  return { tools };
}

export interface UserMemoryVO {
    id: string;
    userId: string;
    sessionId?: string;
    memoryType: string;
    content: string;
    expiresAt?: string;
}

export interface UserMemoryCandidateVO {
  id: string;
  userId: string;
  sessionId?: string;
  memoryType: string;
  content: string;
  evidence?: string;
}

export interface GetUserMemoriesResponse {
  memories: UserMemoryVO[];
}

export interface GetUserMemoryCandidatesResponse {
  candidates: UserMemoryCandidateVO[];
}

export interface UpdateUserMemoryRequest {
  content: string;
}

export interface UpdateUserMemoryExpirationRequest {
  expiresAt: string;
}

export async function getUserMemories(): Promise<GetUserMemoriesResponse> {
  return get<GetUserMemoriesResponse>("/users/memories");
}

export async function getUserMemoryCandidates(): Promise<GetUserMemoryCandidatesResponse> {
  return get<GetUserMemoryCandidatesResponse>("/users/memory-candidates");
}

export async function confirmUserMemoryCandidate(
  candidateId: string,
): Promise<void> {
  return post<void>(`/users/memory-candidates/${candidateId}/confirm`);
}

export async function discardUserMemoryCandidate(
  candidateId: string,
): Promise<void> {
  return post<void>(`/users/memory-candidates/${candidateId}/discard`);
}

export async function updateUserMemory(
  memoryId: string,
  request: UpdateUserMemoryRequest,
): Promise<void> {
  return patch<void>(`/users/memories/${memoryId}`, request);
}

export async function updateUserMemoryExpiration(
  memoryId: string,
  request: UpdateUserMemoryExpirationRequest,
): Promise<void> {
  return patch<void>(`/users/memories/${memoryId}/expiration`, request);
}

export async function deleteUserMemory(
  memoryId: string,
): Promise<void> {
  return del<void>(`/users/memories/${memoryId}`);
}

export async function clearUserMemories(): Promise<void> {
  return del<void>("/users/memories");
}

// ========== Auth APIs ==========

export interface LoginRequest {
  account?: string;
  email?: string;
  password?: string;
  verifyCode?: string;
}

export interface RegisterRequest {
  account: string;
  username: string;
  password: string;
  email?: string;
  verifyCode?: string;
}

export interface LoginResponse {
  userId: number;
  account: string;
  username: string;
  avatarUrl?: string;
  isAdmin: number;
  email?: string;
  token: string;
}

export interface RegisterResponse {
  userId: number;
  token: string;
}

export async function loginUser(request: LoginRequest): Promise<LoginResponse> {
  return post<LoginResponse>("/users/login", request);
}

export async function registerUser(request: RegisterRequest): Promise<RegisterResponse> {
  return post<RegisterResponse>("/users", request);
}

export async function whoami(): Promise<LoginResponse> {
  return get<LoginResponse>("/users/whoami");
}
