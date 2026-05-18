import React, { useMemo, useState } from "react";
import { Card, Space, Typography, Select } from "antd";
import {
  BulbOutlined,
  MessageOutlined,
  RobotOutlined,
  DownOutlined,
} from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { useNavigate } from "react-router-dom";
import {
  type AgentVO,
  createChatMessage,
  createChatSession,
} from "../../../api/api.ts";
import { getAgentEmoji } from "../../../utils";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
import { useUser } from "../../../hooks/useUser.ts";

const { Title, Text } = Typography;

interface EmptyAgentChatViewProps {
  loading: boolean;
  agents: AgentVO[];
}

const EmptyAgentChatView: React.FC<EmptyAgentChatViewProps> = ({
  loading,
  agents,
}) => {
  const [message, setMessage] = useState("");
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null);
  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();
  const { userId } = useUser();

  const agentsWithEmoji = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      emoji: getAgentEmoji(agent.id),
    }));
  }, [agents]);

  const effectiveAgentId = useMemo(() => {
    if (selectedAgentId) {
      return selectedAgentId;
    }
    return agents.length > 0 ? agents[0].id : null;
  }, [selectedAgentId, agents]);

  return (
    <div className="flex flex-col h-full">
      {agents.length > 0 && (
        <div className="border-b border-gray-200 bg-white px-4 py-3">
          <Select
            value={effectiveAgentId}
            onChange={(value) => setSelectedAgentId(value)}
            style={{ width: 220 }}
            className="agent-selector"
            suffixIcon={<DownOutlined className="text-gray-400" />}
            placeholder="选择智能体"
            optionRender={(option) => (
              <div className="flex items-center gap-2">
                <span className="text-lg">
                  {agentsWithEmoji.find((a) => a.id === option.value)?.emoji}
                </span>
                <span className="text-sm">{option.label}</span>
              </div>
            )}
            options={agentsWithEmoji.map((agent) => ({
              value: agent.id,
              label: agent.name,
            }))}
          />
        </div>
      )}

      <div className="flex-1 flex items-center justify-center p-6">
        <div className="max-w-2xl w-full space-y-6">
          <div className="text-center mb-8">
            <Title level={2} className="mb-2">
              开始新的对话
            </Title>
            <Text type="secondary" className="text-base">
              选择一个智能体开始聊天，当前用户边界由显式 userId 控制。
            </Text>
          </div>

          <Space orientation="vertical" size="large" className="w-full">
            <Card hoverable className="cursor-pointer transition-all hover:shadow-lg">
              <Space size="middle">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-blue-400 to-cyan-500 flex items-center justify-center">
                  <RobotOutlined className="text-white text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    智能对话
                  </Title>
                  <Text type="secondary">和 AI 助手对话，自动结合当前会话与长期记忆。</Text>
                </div>
              </Space>
            </Card>

            <Card hoverable className="cursor-pointer transition-all hover:shadow-lg">
              <Space size="middle">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-emerald-400 to-teal-500 flex items-center justify-center">
                  <BulbOutlined className="text-white text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    记忆沉淀
                  </Title>
                  <Text type="secondary">候选记忆需要你确认后才会进入长期上下文。</Text>
                </div>
              </Space>
            </Card>

            <Card hoverable className="cursor-pointer transition-all hover:shadow-lg">
              <Space size="middle">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-amber-400 to-rose-500 flex items-center justify-center">
                  <MessageOutlined className="text-white text-xl" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    快速开始
                  </Title>
                  <Text type="secondary">直接在底部输入消息，自动创建带 userId 的新会话。</Text>
                </div>
              </Space>
            </Card>
          </Space>
        </div>
      </div>

      <div className="border-t border-gray-200 bg-white px-4 pb-4 pt-4">
        <Sender
          onSubmit={async () => {
            if (!effectiveAgentId || !message.trim()) {
              return;
            }
            const response = await createChatSession({
              userId,
              agentId: effectiveAgentId,
              title: message.slice(0, 20),
            });
            await createChatMessage({
              userId,
              sessionId: response.chatSessionId ?? "",
              content: message,
              role: "user",
              agentId: effectiveAgentId,
            });
            await refreshChatSessions();
            setMessage("");
            navigate(`/chat/${response.chatSessionId}`);
          }}
          value={message}
          loading={loading}
          placeholder="输入消息开始对话..."
          onChange={setMessage}
        />
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
