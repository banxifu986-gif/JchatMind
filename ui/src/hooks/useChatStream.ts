import { useCallback, useEffect, useRef, useState } from "react";
import { message as antdMessage } from "antd";
import {
  ChatSseHttpError,
  getChatMessagesBySessionId,
  getChatSession,
  getPendingApprovals,
  runSseReconnectLoop,
  subscribeChatSessionEvents,
  type PendingApprovalVO,
} from "../api/api.ts";
import { mergeChatHistoryWithLiveMessages } from "../utils/chatHistory.ts";
import type { AgentExecutionTraceItem } from "../components/views/agentChatView/AgentChatHistory.tsx";
import type { ChatMessageVO, SseMessageType } from "../types";

interface UseChatStreamOptions {
  chatSessionId: string | undefined;
  sessionTraceCache?: Map<string, AgentExecutionTraceItem[]>;
  initialAgentTrace: AgentExecutionTraceItem[];
}

export function useChatStream({
  chatSessionId,
  sessionTraceCache,
  initialAgentTrace,
}: UseChatStreamOptions) {
  const seenMessageIdsRef = useRef<Set<string>>(new Set());
  const streamingSessionIdRef = useRef<string | undefined>(undefined);
  const lastEventIdRef = useRef<number | undefined>(undefined);
  const lastEventSessionIdRef = useRef<string | undefined>(undefined);
  const historyRecoveryMessagesRef = useRef<ChatMessageVO[] | null>(null);

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [agentId, setAgentId] = useState<string>("");
  const [displayAgentStatus, setDisplayAgentStatus] = useState(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<SseMessageType>();
  const [agentTrace, setAgentTrace] = useState<AgentExecutionTraceItem[]>(initialAgentTrace);
  const [streamingContent, setStreamingContent] = useState("");
  const [streamingSessionId, setStreamingSessionId] = useState<string | undefined>(undefined);
  const [pendingApproval, setPendingApproval] =
    useState<PendingApprovalVO | null>(null);

  useEffect(() => {
    if (chatSessionId) {
      sessionTraceCache?.set(chatSessionId, agentTrace);
    }
  }, [agentTrace, chatSessionId, sessionTraceCache]);

  const addMessage = (message: ChatMessageVO) => {
    historyRecoveryMessagesRef.current?.push(message);
    if (seenMessageIdsRef.current.has(message.id)) return;
    seenMessageIdsRef.current.add(message.id);
    setMessages((prevMessages) => [...prevMessages, message]);
  };

  const recordAgentTrace = useCallback((
    type: SseMessageType,
    statusText: string,
    stepNumber?: number,
  ) => {
    setAgentTrace((previousTrace) => {
      const traceItem: AgentExecutionTraceItem = {
        id: (previousTrace.at(-1)?.id ?? -1) + 1,
        type,
        statusText,
        stepNumber,
      };

      let nextTrace: AgentExecutionTraceItem[];
      if (type === "AI_PLANNING") {
        nextTrace = [traceItem];
      } else {
        const lastItem = previousTrace.at(-1);
        if (
          lastItem?.type === type
          && lastItem.statusText === statusText
          && lastItem.stepNumber === stepNumber
        ) {
          return previousTrace;
        }
        nextTrace = [...previousTrace, traceItem].slice(-20);
      }

      return nextTrace;
    });
  }, []);

  const getChatMessages = useCallback(async (liveMessagesDuringRecovery?: ChatMessageVO[]) => {
    if (!chatSessionId) {
      return;
    }
    const resp = await getChatMessagesBySessionId(chatSessionId);
    const sessionResp = await getChatSession(chatSessionId);
    const sessionMessages = liveMessagesDuringRecovery
      ? mergeChatHistoryWithLiveMessages(resp.chatMessages, liveMessagesDuringRecovery)
      : resp.chatMessages;
    seenMessageIdsRef.current = new Set(sessionMessages.map((message) => message.id));
    setMessages(sessionMessages);
    setAgentId(sessionResp.chatSession.agentId);
    return sessionMessages;
  }, [chatSessionId]);

  const refreshPendingApproval = useCallback(async () => {
    if (!chatSessionId) {
      return;
    }

    try {
      const response = await getPendingApprovals(chatSessionId);
      setPendingApproval(response.approvals[0] ?? null);
    } catch (error) {
      console.error("加载待审批工具失败:", error);
    }
  }, [chatSessionId]);

  const beginNewRound = useCallback(() => {
    if (!chatSessionId) {
      return;
    }
    setAgentTrace([]);
    sessionTraceCache?.set(chatSessionId, []);
    streamingSessionIdRef.current = chatSessionId;
    setStreamingSessionId(chatSessionId);
    setStreamingContent("");
  }, [chatSessionId, sessionTraceCache]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    if (lastEventSessionIdRef.current !== chatSessionId) {
      lastEventSessionIdRef.current = chatSessionId;
      lastEventIdRef.current = undefined;
    }
    let active = true;
    const controller = new AbortController();
    void runSseReconnectLoop(
      () => subscribeChatSessionEvents(chatSessionId, (message, eventId) => {
        if (eventId !== undefined) {
          if (lastEventIdRef.current !== undefined && eventId <= lastEventIdRef.current) {
            return;
          }
          lastEventIdRef.current = eventId;
        }
        if (message.type === "AI_GENERATED_CONTENT") {
          const isFinalAssistantMessage = message.payload.message.role === "assistant"
            && !message.payload.message.metadata?.toolCalls?.length;
          setStreamingContent("");
          addMessage(message.payload.message);
          if (isFinalAssistantMessage) {
            setDisplayAgentStatus(false);
            setAgentStatusText("");
            setAgentStatusType(undefined);
          }
          return;
        }
        if (message.type === "AI_CONTENT_DELTA") {
          if (message.payload.contentDelta) {
            if (streamingSessionIdRef.current !== chatSessionId) {
              streamingSessionIdRef.current = chatSessionId;
              setStreamingSessionId(chatSessionId);
              setStreamingContent(message.payload.contentDelta);
            } else {
              setStreamingContent((previousContent) => previousContent + message.payload.contentDelta);
            }
          }
          return;
        }
        if (message.type === "AI_DONE") {
          recordAgentTrace(
            message.type,
            message.payload.statusText || "任务完成",
            message.payload.stepNumber,
          );
          setDisplayAgentStatus(false);
          setAgentStatusText("");
          setAgentStatusType(undefined);
          setPendingApproval(null);
          return;
        }
        if (message.type === "AI_ERROR") {
          recordAgentTrace(
            message.type,
            message.payload.statusText || "Agent 执行失败，请稍后重试",
            message.payload.stepNumber,
          );
          setPendingApproval(null);
          setDisplayAgentStatus(true);
          setAgentStatusText(message.payload.statusText || "Agent 执行失败，请稍后重试");
          setAgentStatusType(message.type);
          antdMessage.error(message.payload.statusText || "Agent 执行失败，请稍后重试");
          return;
        }
        if (message.type === "TOOL_APPROVAL_REQUIRED") {
          const payload = message.payload;
          if (payload.approvalRequestId && payload.toolName) {
            setPendingApproval({
              id: payload.approvalRequestId,
              sessionId: chatSessionId,
              toolName: payload.toolName,
              toolInput: payload.toolInput ?? "",
              callCount: payload.callCount ?? 1,
              status: "PENDING",
              createdAt: Date.now(),
              expiresAt: payload.expiresAt ?? 0,
            });
            refreshPendingApproval().then();
          }
        }
        recordAgentTrace(
          message.type,
          message.payload.statusText,
          message.payload.stepNumber,
        );
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType(message.type);
      }, controller.signal, lastEventIdRef.current, (eventId) => {
        if (eventId !== undefined) {
          lastEventIdRef.current = eventId;
        }
        const recoveryMessages: ChatMessageVO[] = [];
        historyRecoveryMessagesRef.current = recoveryMessages;
        setStreamingContent("");
        void getChatMessages(recoveryMessages).then((sessionMessages) => {
          if (eventId !== undefined
            && (lastEventIdRef.current === undefined || eventId > lastEventIdRef.current)) {
            lastEventIdRef.current = eventId;
          }
          const latestMessage = sessionMessages?.at(-1);
          if (latestMessage?.role === "assistant"
            && !latestMessage.metadata?.toolCalls?.length) {
            setDisplayAgentStatus(false);
            setAgentStatusText("");
            setAgentStatusType(undefined);
          }
        }).catch((error: unknown) => {
          console.error("恢复聊天历史失败:", error);
        }).finally(() => {
          if (historyRecoveryMessagesRef.current === recoveryMessages) {
            historyRecoveryMessagesRef.current = null;
          }
        });
        void refreshPendingApproval();
      }),
      controller.signal,
      () => active,
      (error: unknown) => {
        if (error instanceof ChatSseHttpError && error.status >= 400 && error.status < 500) {
          console.error("聊天 SSE 请求被拒绝:", error);
          return false;
        }
        console.error("SSE error:", error);
        return true;
      },
    );

    return () => {
      active = false;
      controller.abort();
    };
  }, [chatSessionId, getChatMessages, recordAgentTrace, refreshPendingApproval]);

  return {
    messages,
    agentId,
    agentTrace,
    streamingContent: streamingSessionId === chatSessionId ? streamingContent : "",
    displayAgentStatus,
    agentStatusText,
    agentStatusType,
    pendingApproval,
    setPendingApproval,
    getChatMessages,
    refreshPendingApproval,
    beginNewRound,
  };
}
