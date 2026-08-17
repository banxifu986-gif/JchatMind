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
): Promise<void> {
  return del<void>(`/knowledge-bases/${knowledgeBaseId}`);
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
}

export async function getDocumentsByKbId(
  kbId: string,
): Promise<GetDocumentsResponse> {
  return get<GetDocumentsResponse>(`/documents/kb/${kbId}`);
}

export async function uploadDocument(
  kbId: string,
  file: File,
): Promise<CreateDocumentResponse> {
  const token = window.localStorage.getItem("jchatmind.token");
  const headers: Record<string, string> = {};
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

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

export async function deleteUserMemory(
  memoryId: string,
): Promise<void> {
  return del<void>(`/users/memories/${memoryId}`);
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
