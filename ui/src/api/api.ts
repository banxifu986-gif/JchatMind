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
  userId: string;
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

export async function getChatSessions(
  userId: string,
): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>("/chat-sessions", { userId });
}

export async function getChatSession(
  userId: string,
  chatSessionId: string,
): Promise<GetChatSessionResponse> {
  return get<GetChatSessionResponse>(`/chat-sessions/${chatSessionId}`, {
    userId,
  });
}

export async function getChatSessionsByAgentId(
  userId: string,
  agentId: string,
): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>(`/chat-sessions/agent/${agentId}`, {
    userId,
  });
}

export async function updateChatSession(
  userId: string,
  chatSessionId: string,
  request: UpdateChatSessionRequest,
): Promise<void> {
  return patch<void>(`/chat-sessions/${chatSessionId}?userId=${encodeURIComponent(userId)}`, request);
}

export async function deleteChatSession(
  userId: string,
  chatSessionId: string,
): Promise<void> {
  return del<void>(`/chat-sessions/${chatSessionId}`, { userId });
}

export interface MetaData {
  [key: string]: unknown;
}

export interface GetChatMessagesResponse {
  chatMessages: ChatMessageVO[];
}

export interface CreateChatMessageRequest {
  userId: string;
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
  userId: string,
  sessionId: string,
): Promise<GetChatMessagesResponse> {
  return get<GetChatMessagesResponse>(`/chat-messages/session/${sessionId}`, {
    userId,
  });
}

export async function createChatMessage(
  request: CreateChatMessageRequest,
): Promise<CreateChatMessageResponse> {
  return post<CreateChatMessageResponse>("/chat-messages", request);
}

export async function updateChatMessage(
  userId: string,
  chatMessageId: string,
  request: UpdateChatMessageRequest,
): Promise<void> {
  return patch<void>(`/chat-messages/${chatMessageId}?userId=${encodeURIComponent(userId)}`, request);
}

export async function deleteChatMessage(
  userId: string,
  chatMessageId: string,
): Promise<void> {
  return del<void>(`/chat-messages/${chatMessageId}`, { userId });
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
  const formData = new FormData();
  formData.append("kbId", kbId);
  formData.append("file", file);

  const response = await fetch(`${BASE_URL}/documents/upload`, {
    method: "POST",
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

export async function getUserMemories(
  userId: string,
): Promise<GetUserMemoriesResponse> {
  return get<GetUserMemoriesResponse>(`/users/${encodeURIComponent(userId)}/memories`);
}

export async function getUserMemoryCandidates(
  userId: string,
): Promise<GetUserMemoryCandidatesResponse> {
  return get<GetUserMemoryCandidatesResponse>(
    `/users/${encodeURIComponent(userId)}/memory-candidates`,
  );
}

export async function confirmUserMemoryCandidate(
  userId: string,
  candidateId: string,
): Promise<void> {
  return post<void>(
    `/users/${encodeURIComponent(userId)}/memory-candidates/${candidateId}/confirm`,
  );
}

export async function deleteUserMemory(
  userId: string,
  memoryId: string,
): Promise<void> {
  return del<void>(`/users/${encodeURIComponent(userId)}/memories/${memoryId}`);
}
