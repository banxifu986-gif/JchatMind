import React, { useCallback, useEffect, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { message as antdMessage } from "antd";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createChatMessage,
  createChatSession,
  getChatMessagesBySessionId,
  getChatSession,
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
  const { state } = useLocation();
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();
  const { userId } = useUser();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [agentId, setAgentId] = useState<string>("");
  const [displayAgentStatus, setDisplayAgentStatus] = useState(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<SseMessageType>();

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => [...prevMessages, message]);
  };

  const getChatMessages = useCallback(async () => {
    if (!chatSessionId) {
      return;
    }
    const resp = await getChatMessagesBySessionId(userId, chatSessionId);
    setMessages(resp.chatMessages);

    const sessionResp = await getChatSession(userId, chatSessionId);
    setAgentId(sessionResp.chatSession.agentId);
  }, [chatSessionId, userId]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    getChatMessages().then();
  }, [chatSessionId, getChatMessages]);

  const handleSendMessage = async (value: string | { text: string }) => {
    const message = typeof value === "string" ? value : value.text;
    if (!message || !message.trim()) {
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
          userId,
          agentId,
          title: message.slice(0, 20),
        });
        await refreshChatSessions();
        navigate(`/chat/${response.chatSessionId}`, {
          replace: true,
          state: {
            init: false,
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

    if (state?.init) {
      await createChatMessage({
        userId,
        agentId: agentId ?? "",
        sessionId: chatSessionId,
        role: "user",
        content: state.initMessage ?? "",
      });
    } else {
      await createChatMessage({
        userId,
        agentId: agentId ?? "",
        sessionId: chatSessionId,
        role: "user",
        content: message,
      });
    }
    await getChatMessages();
  };

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    const es = new EventSource(`${SSE_BASE_URL}/connect/${chatSessionId}`);
    es.onmessage = () => {};
    es.onerror = (error) => {
      console.error("SSE error:", error);
    };

    es.addEventListener("message", (event) => {
      const message = JSON.parse(event.data) as SseMessage;
      if (message.type === "AI_GENERATED_CONTENT") {
        addMessage(message.payload.message);
        return;
      }
      if (message.type === "AI_DONE") {
        setDisplayAgentStatus(false);
        setAgentStatusText("");
        setAgentStatusType(undefined);
        return;
      }
      setDisplayAgentStatus(true);
      setAgentStatusText(message.payload.statusText);
      setAgentStatusType(message.type);
    });

    return () => {
      es.close();
    };
  }, [chatSessionId]);

  if (!chatSessionId) {
    return <EmptyAgentChatView agents={agents} loading={loading} />;
  }

  return (
    <div className="flex flex-col h-full">
      <AgentChatHistory
        messages={messages}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
      />
      <div className="border-t border-gray-200 p-4 bg-white">
        <AgentChatInput onSend={handleSendMessage} />
      </div>
    </div>
  );
};

export default AgentChatView;
