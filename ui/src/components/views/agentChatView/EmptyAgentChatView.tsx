import React, { useMemo, useState } from "react";
import { message as antdMessage, Select, Typography } from "antd";
import { DownOutlined, RobotOutlined } from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { useLocation, useNavigate } from "react-router-dom";
import {
  type AgentVO,
  createChatSession,
} from "../../../api/api.ts";
import { getAgentAvatar } from "../../../utils";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
import { useUser } from "../../../hooks/useUser.ts";

const { Title, Text } = Typography;

const QUICK_PROMPTS = [
  "帮我分析一个问题",
  "从知识库中查找资料",
  "整理并沉淀我的记忆",
  "帮我规划下一步",
] as const;

interface EmptyAgentChatViewProps {
  loading: boolean;
  agents: AgentVO[];
}

const EmptyAgentChatView: React.FC<EmptyAgentChatViewProps> = ({
  loading,
  agents,
}) => {
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { refreshChatSessions } = useChatSessions();
  const { isLogin } = useUser();
  const agentsWithAvatar = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      ...getAgentAvatar(agent.id, agent.name),
    }));
  }, [agents]);

  const preselectedAgentId = (location.state as { selectedAgentId?: string })
    ?.selectedAgentId;
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(
    () => preselectedAgentId ?? null,
  );

  const effectiveAgentId = useMemo(() => {
    if (selectedAgentId && agents.some((agent) => agent.id === selectedAgentId)) {
      return selectedAgentId;
    }
    return agents.length > 0 ? agents[0].id : null;
  }, [agents, selectedAgentId]);

  const effectiveAgent = useMemo(() => {
    return agentsWithAvatar.find((agent) => agent.id === effectiveAgentId) ?? null;
  }, [agentsWithAvatar, effectiveAgentId]);

  const handleSubmit = async () => {
    if (submitting) {
      return;
    }
    if (!isLogin) {
      antdMessage.warning("请先登录");
      return;
    }
    if (!effectiveAgentId || !message.trim()) {
      return;
    }

    const initialMessage = message.trim();
    setSubmitting(true);
    try {
      const response = await createChatSession({
        agentId: effectiveAgentId,
        title: initialMessage.slice(0, 20),
      });
      await refreshChatSessions();
      setMessage("");
      navigate(`/chat/${response.chatSessionId}`, {
        replace: true,
        state: {
          init: true,
          initMessage: initialMessage,
        },
      });
    } catch (error) {
      antdMessage.error(error instanceof Error ? error.message : "创建聊天会话失败，请重试");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="app-chat-page app-chat-page--empty">
      {agents.length > 0 && (
        <div className="app-chat__topbar">
          <div className="app-chat__agent-context">
            <span
              className={`app-chat__agent-avatar bg-gradient-to-br ${effectiveAgent?.gradientClass ?? "from-slate-400 to-slate-500"}`}
            >
              {effectiveAgent?.initial ?? "A"}
            </span>
            <div className="app-chat__agent-select-copy">
              <span className="app-chat__eyebrow">当前智能体</span>
              <Select
                value={effectiveAgentId}
                onChange={(value) => setSelectedAgentId(value)}
                variant="borderless"
                className="app-chat__agent-select"
                suffixIcon={<DownOutlined />}
                placeholder="选择智能体"
                optionRender={(option) => {
                  const agent = agentsWithAvatar.find((item) => item.id === option.value);
                  return (
                    <div className="app-chat__agent-option">
                      <span
                        className={`app-chat__agent-option-avatar bg-gradient-to-br ${agent?.gradientClass ?? "from-slate-400 to-slate-500"}`}
                      >
                        {agent?.initial}
                      </span>
                      <span>{option.label}</span>
                    </div>
                  );
                }}
                options={agentsWithAvatar.map((agent) => ({
                  value: agent.id,
                  label: agent.name,
                }))}
              />
            </div>
          </div>
          <span className="app-chat__topbar-hint">准备好开始工作</span>
        </div>
      )}

      <div className="app-chat__empty-main">
        <div className="app-chat__signal-mark" aria-hidden="true">
          <span className="app-chat__signal-ring" />
          <span className="app-chat__signal-core">
            <RobotOutlined />
          </span>
        </div>
        <span className="app-chat__eyebrow app-chat__empty-eyebrow">JCHATMIND WORKSPACE</span>
        <Title level={1} className="app-chat__empty-title">
          你想让 JChatMind 帮你构建什么？
        </Title>
        <Text className="app-chat__empty-subtitle">
          {effectiveAgent
            ? `从一个想法开始，让 ${effectiveAgent.name} 帮你拆解、检索和执行。`
            : "从左侧工作区添加一个智能体，开始你的第一段对话。"}
        </Text>

        <div className="app-chat__prompt-list" aria-label="快捷提示">
          {QUICK_PROMPTS.map((prompt) => (
            <button
              type="button"
              className="app-chat__prompt-chip"
              key={prompt}
              onClick={() => setMessage(prompt)}
            >
              {prompt}
            </button>
          ))}
        </div>
      </div>

      <div className="app-chat__composer-area">
        <div className="app-chat__composer-shell">
          <Sender
            onSubmit={handleSubmit}
            value={message}
            loading={loading || submitting}
            disabled={!effectiveAgentId || submitting}
            placeholder={
              effectiveAgent
                ? `向 ${effectiveAgent.name} 发送消息...`
                : "请先在左侧工作区添加智能体"
            }
            onChange={setMessage}
          />
        </div>
        <span className="app-chat__composer-note">
          JChatMind 可能会出错，请核对重要信息
        </span>
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
