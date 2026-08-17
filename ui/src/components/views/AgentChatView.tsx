import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { message as antdMessage } from "antd";
import { RobotOutlined } from "@ant-design/icons";
import AgentChatHistory, {
  type AgentExecutionTraceItem,
} from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  approveHarnessRequest,
  createChatMessage,
  createChatSession,
  getChatMessagesBySessionId,
  getPendingApprovals,
  getChatSession,
  rejectHarnessRequest,
  type PendingApprovalVO,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { useUser } from "../../hooks/useUser.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";

const SSE_BASE_URL = import.meta.env.VITE_SSE_BASE_URL;

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const state = location.state;
  const initProcessedRef = useRef(false);
  const seenMessageIdsRef = useRef<Set<string>>(new Set());
  const traceSequenceRef = useRef(0);
  const streamingSessionIdRef = useRef<string | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();
  const { isLogin } = useUser();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [agentId, setAgentId] = useState<string>("");
  const [displayAgentStatus, setDisplayAgentStatus] = useState(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<SseMessageType>();
  const [agentTrace, setAgentTrace] = useState<AgentExecutionTraceItem[]>([]);
  const [streamingContent, setStreamingContent] = useState("");
  const [pendingApproval, setPendingApproval] =
    useState<PendingApprovalVO | null>(null);
  const [approvalSubmitting, setApprovalSubmitting] = useState(false);

  const addMessage = (message: ChatMessageVO) => {
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
        id: traceSequenceRef.current++,
        type,
        statusText,
        stepNumber,
      };

      if (type === "AI_PLANNING") {
        return [traceItem];
      }

      const lastItem = previousTrace.at(-1);
      if (
        lastItem?.type === type
        && lastItem.statusText === statusText
        && lastItem.stepNumber === stepNumber
      ) {
        return previousTrace;
      }

      return [...previousTrace, traceItem].slice(-20);
    });
  }, []);

  const getChatMessages = useCallback(async () => {
    if (!chatSessionId) {
      return;
    }
    const resp = await getChatMessagesBySessionId(chatSessionId);
    seenMessageIdsRef.current = new Set(resp.chatMessages.map((m) => m.id));
    setMessages(resp.chatMessages);

    const sessionResp = await getChatSession(chatSessionId);
    setAgentId(sessionResp.chatSession.agentId);
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

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    getChatMessages().then();
  }, [chatSessionId, getChatMessages]);

  useEffect(() => {
    refreshPendingApproval().then();
  }, [refreshPendingApproval]);

  useEffect(() => {
    if (!state?.init || !chatSessionId || !agentId || initProcessedRef.current) {
      return;
    }
    initProcessedRef.current = true;

    createChatMessage({
      agentId,
      sessionId: chatSessionId,
      role: "user",
      content: state.initMessage ?? "",
    }).then(() => getChatMessages());

    navigate(location.pathname, { replace: true });
  }, [state?.init, chatSessionId, agentId]);

  const handleSendMessage = async (value: string | { text: string }) => {
    const message = typeof value === "string" ? value : value.text;
    if (!message || !message.trim()) {
      return;
    }

    if (!isLogin) {
      antdMessage.warning("请先登录");
      return;
    }

    if (!chatSessionId) {
      if (!agentId) {
        antdMessage.warning("请先选择一个智能体");
        return;
      }
      setLoading(true);
      try {
        const response = await createChatSession({
          agentId,
          title: message.slice(0, 20),
        });
        await refreshChatSessions();
        navigate(`/chat/${response.chatSessionId}`, {
          replace: true,
          state: {
            init: true,
            initMessage: message,
          },
        });
      } catch (error) {
        console.error("创建聊天会话失败:", error);
        antdMessage.error("创建聊天会话失败，请重试");
      } finally {
        setLoading(false);
      }
      return;
    }

    try {
      setAgentTrace([]);
      streamingSessionIdRef.current = chatSessionId;
      setStreamingContent("");
      await createChatMessage({
        agentId: agentId ?? "",
        sessionId: chatSessionId,
        role: "user",
        content: message,
      });
      await getChatMessages();
    } catch (error) {
      console.error("发送消息失败:", error);
      antdMessage.error("发送消息失败，请重试");
    }
  };

  const handleApproval = async (approved: boolean) => {
    if (!pendingApproval) {
      return;
    }

    setApprovalSubmitting(true);
    try {
      if (approved) {
        await approveHarnessRequest(pendingApproval.id);
        antdMessage.success("已批准工具执行");
      } else {
        await rejectHarnessRequest(pendingApproval.id);
        antdMessage.success("已拒绝工具执行");
      }
      setPendingApproval(null);
    } catch (error) {
      console.error("提交审批决策失败:", error);
      antdMessage.error("提交审批决策失败，请重试");
    } finally {
      setApprovalSubmitting(false);
    }
  };

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    const es = new EventSource(`${SSE_BASE_URL}/connect/${chatSessionId}`);
    es.onerror = (error) => {
      console.error("SSE error:", error);
    };

    es.addEventListener("message", (event) => {
      const message = JSON.parse(event.data) as SseMessage;
      if (message.type === "AI_GENERATED_CONTENT") {
        setStreamingContent("");
        addMessage(message.payload.message);
        return;
      }
      if (message.type === "AI_CONTENT_DELTA") {
        if (message.payload.contentDelta) {
          if (streamingSessionIdRef.current !== chatSessionId) {
            streamingSessionIdRef.current = chatSessionId;
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
    });

    return () => {
      es.close();
    };
  }, [chatSessionId, recordAgentTrace, refreshPendingApproval]);

  const agentName = useMemo(() => {
    if (!agentId) return null;
    return agents.find((a) => a.id === agentId)?.name ?? null;
  }, [agentId, agents]);

  if (!chatSessionId) {
    return <EmptyAgentChatView agents={agents} loading={loading} />;
  }

  return (
    <div className="flex flex-col h-full">
      {agentName && (
        <div className="border-b border-gray-200 bg-white px-4 py-2 flex items-center gap-2">
          <RobotOutlined className="text-gray-400" />
          <span className="text-sm text-gray-600">当前智能体：</span>
          <span className="text-sm font-medium text-gray-900">{agentName}</span>
        </div>
      )}
      <AgentChatHistory
        messages={messages}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
        agentTrace={agentTrace}
        streamingContent={streamingSessionIdRef.current === chatSessionId ? streamingContent : ""}
        pendingApproval={pendingApproval}
        approvalSubmitting={approvalSubmitting}
        onApprove={() => handleApproval(true)}
        onReject={() => handleApproval(false)}
      />
      <div className="border-t border-gray-200 p-4 bg-white">
        <AgentChatInput onSend={handleSendMessage} />
      </div>
    </div>
  );
};

export default AgentChatView;
