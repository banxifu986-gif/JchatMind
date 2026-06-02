import React, { useMemo, useState } from "react";
import { Card, Space, Typography, Select, Tag } from "antd";
import {
  BulbOutlined,
  MessageOutlined,
  RobotOutlined,
  DownOutlined,
} from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { useNavigate, useLocation } from "react-router-dom";
import {
  type AgentVO,
  createChatSession,
} from "../../../api/api.ts";
import { getAgentAvatar } from "../../../utils";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
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
  const navigate = useNavigate();
  const location = useLocation();
  const { refreshChatSessions } = useChatSessions();
  const agentsWithEmoji = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      ...getAgentAvatar(agent.id, agent.name),
    }));
  }, [agents]);

  const preselectedAgentId = (location.state as { selectedAgentId?: string })?.selectedAgentId;
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(
    () => preselectedAgentId ?? null,
  );

  const effectiveAgentId = useMemo(() => {
    if (selectedAgentId && agents.some((a) => a.id === selectedAgentId)) {
      return selectedAgentId;
    }
    return agents.length > 0 ? agents[0].id : null;
  }, [selectedAgentId, agents]);

  const effectiveAgent = useMemo(() => {
    return agentsWithEmoji.find((a) => a.id === effectiveAgentId) ?? null;
  }, [agentsWithEmoji, effectiveAgentId]);

  return (
    <div className="flex flex-col h-full">
      {agents.length > 0 && (
        <div className="border-b border-gray-200 bg-white px-4 py-3 flex items-center gap-3">
          <Select
            value={effectiveAgentId}
            onChange={(value) => setSelectedAgentId(value)}
            style={{ width: 220 }}
            className="agent-selector"
            suffixIcon={<DownOutlined className="text-gray-400" />}
            placeholder="选择智能体"
            optionRender={(option) => {
              const agent = agentsWithEmoji.find((a) => a.id === option.value);
              return (
                <div className="flex items-center gap-2">
                  <span className={`inline-flex w-5 h-5 rounded bg-gradient-to-br ${agent?.gradientClass} items-center justify-center text-white text-[10px] font-semibold`}>
                    {agent?.initial}
                  </span>
                  <span className="text-sm">{option.label}</span>
                </div>
              );
            }}
            options={agentsWithEmoji.map((agent) => ({
              value: agent.id,
              label: agent.name,
            }))}
          />
          {effectiveAgent && (
            <Tag color="blue">
              当前：{effectiveAgent.name}
            </Tag>
          )}
        </div>
      )}

      <div className="flex-1 flex items-center justify-center p-6">
        <div className="max-w-5xl w-full space-y-6">
          <div className="text-center mb-8">
            <Title level={2} className="mb-2">
              开始新的对话
            </Title>
            <Text type="secondary" className="text-base">
              {effectiveAgent
                ? `当前智能体：${effectiveAgent.name}，在下方输入消息即可开始对话。`
                : "请先在左侧「智能体」标签中创建一个智能体助手。"}
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
                  <Text type="secondary">直接在底部输入消息，自动创建新会话。</Text>
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
              agentId: effectiveAgentId,
              title: message.slice(0, 20),
            });
            await refreshChatSessions();
            setMessage("");
            navigate(`/chat/${response.chatSessionId}`, {
              replace: true,
              state: {
                init: true,
                initMessage: message,
              },
            });
          }}
          value={message}
          loading={loading}
          placeholder={
            effectiveAgent
              ? `向 ${effectiveAgent.name} 发送消息...`
              : "请先选择智能体"
          }
          onChange={setMessage}
        />
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
