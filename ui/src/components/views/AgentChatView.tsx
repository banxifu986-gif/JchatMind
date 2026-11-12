import React, { useEffect, useMemo, useRef, useState } from "react";
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
  rejectHarnessRequest,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatStream } from "../../hooks/useChatStream.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { useUser } from "../../hooks/useUser.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";

interface AgentChatViewProps {
  sessionTraceCache?: Map<string, AgentExecutionTraceItem[]>;
}

const AgentChatView: React.FC<AgentChatViewProps> = ({ sessionTraceCache }) => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const state = location.state;
  const initialAgentTrace = chatSessionId
    ? sessionTraceCache?.get(chatSessionId) ?? []
    : [];
  const initProcessedRef = useRef(false);
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();
  const { isLogin } = useUser();

  const {
    messages,
    agentId,
    agentTrace,
    streamingContent,
    displayAgentStatus,
    agentStatusText,
    agentStatusType,
    pendingApproval,
    setPendingApproval,
    getChatMessages,
    refreshPendingApproval,
    beginNewRound,
  } = useChatStream({ chatSessionId, sessionTraceCache, initialAgentTrace });

  const [approvalOutcome, setApprovalOutcome] = useState<"approved" | "rejected" | null>(null);
  const [approvalSubmitting, setApprovalSubmitting] = useState(false);

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
  }, [state?.init, state?.initMessage, chatSessionId, agentId, getChatMessages, navigate, location.pathname]);

  const handleSendMessage = async (value: string | { text: string }) => {
    const message = typeof value === "string" ? value : value.text;
    if (!message || !message.trim()) {
      return;
    }
    setApprovalOutcome(null);

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
      beginNewRound();
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
        setApprovalOutcome("approved");
        antdMessage.success("已批准工具执行");
      } else {
        await rejectHarnessRequest(pendingApproval.id);
        setApprovalOutcome("rejected");
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

  const activeAgent = useMemo(() => {
    if (!agentId) return null;
    return agents.find((agent) => agent.id === agentId) ?? null;
  }, [agentId, agents]);
  const agentName = activeAgent?.name ?? null;

  if (!chatSessionId) {
    return <EmptyAgentChatView agents={agents} loading={loading} />;
  }

  return (
    <div className="app-chat-page">
      <div className="app-chat__topbar">
        <span className="app-chat__topbar-title">当前对话</span>
        <div className="app-chat__topbar-meta">
          <div className="app-chat__agent-context">
            <span className="app-chat__agent-avatar app-chat__agent-avatar--live">
              <RobotOutlined />
            </span>
            <div className="app-chat__agent-select-copy">
              <span className="app-chat__active-agent-name">{agentName ?? "JChatMind"}</span>
              {activeAgent?.description && (
                <span className="app-chat__agent-description">{activeAgent.description}</span>
              )}
            </div>
          </div>
          <span className="app-chat__topbar-hint">实时工作区</span>
        </div>
      </div>
      <AgentChatHistory
        messages={messages}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
        agentTrace={agentTrace}
        streamingContent={streamingContent}
        pendingApproval={pendingApproval}
        approvalOutcome={approvalOutcome}
        approvalSubmitting={approvalSubmitting}
        onApprove={() => handleApproval(true)}
        onReject={() => handleApproval(false)}
      />
      <div className="app-chat__active-composer">
        <AgentChatInput onSend={handleSendMessage} />
      </div>
    </div>
  );
};

export default AgentChatView;
