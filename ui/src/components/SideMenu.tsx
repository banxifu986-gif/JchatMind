import React, { useState } from "react";
import {
  BookOutlined,
  IdcardOutlined,
  MessageOutlined,
  RobotOutlined,
} from "@ant-design/icons";
import { Tabs, type TabsProps, Input } from "antd";
import { useNavigate, useLocation } from "react-router-dom";
import AgentTabContent from "./tabs/AgentTabContent.tsx";
import AddAgentModal from "./modals/AddAgentModal.tsx";
import ChatTabContent from "./tabs/ChatTabContent.tsx";
import KnowledgeBaseTabContent from "./tabs/KnowledgeBaseTabContent.tsx";
import AddKnowledgeBaseModal from "./modals/AddKnowledgeBaseModal.tsx";
import { useAgents } from "../hooks/useAgents.ts";
import { useKnowledgeBases } from "../hooks/useKnowledgeBases.ts";
import { useUser } from "../hooks/useUser.ts";

const SideMenu: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { userId, setUserId } = useUser();

  const [isAddAgentModalOpen, setIsAddAgentModalOpen] = useState(false);
  const [editingAgent, setEditingAgent] = useState<
    import("../api/api.ts").AgentVO | null
  >(null);
  const [isAddKnowledgeBaseModalOpen, setIsAddKnowledgeBaseModalOpen] =
    useState(false);

  const toggleAddAgentModal = () => {
    setIsAddAgentModalOpen(!isAddAgentModalOpen);
    setEditingAgent(null);
  };

  const toggleAddKnowledgeBaseModal = () => {
    setIsAddKnowledgeBaseModalOpen(!isAddKnowledgeBaseModalOpen);
  };

  const { agents, createAgentHandle, deleteAgentHandle, updateAgentHandle } =
    useAgents();
  const { knowledgeBases, createKnowledgeBaseHandle } = useKnowledgeBases();

  const [activeKey, setActiveKey] = useState(() => {
    if (location.pathname.startsWith("/knowledge-base")) return "knowledgeBase";
    if (location.pathname.startsWith("/chat")) return "chat";
    if (location.pathname.startsWith("/user-memory")) return "memory";
    return "agent";
  });

  const handleTabChange = (key: string) => {
    setActiveKey(key);
    if (key === "agent") navigate("/agent");
    if (key === "chat") navigate("/chat");
    if (key === "knowledgeBase") navigate("/knowledge-base");
    if (key === "memory") navigate("/user-memory");
  };

  const items: TabsProps["items"] = [
    {
      key: "agent",
      label: (
        <span className="select-none flex items-center gap-1">
          <RobotOutlined />
          智能体
        </span>
      ),
      children: (
        <AgentTabContent
          agents={agents}
          onSelectAgent={() => {}}
          onCreateAgentClick={toggleAddAgentModal}
          onEditAgent={(agent) => {
            setEditingAgent(agent);
            setIsAddAgentModalOpen(true);
          }}
          onDeleteAgent={deleteAgentHandle}
        />
      ),
    },
    {
      key: "chat",
      label: (
        <span className="select-none flex items-center gap-1">
          <MessageOutlined />
          会话
        </span>
      ),
      children: <ChatTabContent />,
    },
    {
      key: "memory",
      label: (
        <span className="select-none flex items-center gap-1">
          <IdcardOutlined />
          记忆
        </span>
      ),
      children: (
        <div className="p-2 text-sm text-slate-500">
          点击上方标签进入用户记忆管理页面。
        </div>
      ),
    },
    {
      key: "knowledgeBase",
      label: (
        <span className="select-none flex items-center gap-1">
          <BookOutlined />
          知识库
        </span>
      ),
      children: (
        <KnowledgeBaseTabContent
          knowledgeBases={knowledgeBases}
          onCreateKnowledgeBaseClick={toggleAddKnowledgeBaseModal}
          onSelectKnowledgeBase={(knowledgeBaseId) => {
            navigate(`/knowledge-base/${knowledgeBaseId}`);
          }}
        />
      ),
    },
  ];

  return (
    <div className="px-4 flex flex-col h-full">
      <div className="h-16 w-full flex items-center border-b border-gray-200">
        <div className="w-full">
          <div className="flex items-center gap-2.5 mb-2">
            <RobotOutlined className="text-xl text-orange-600" />
            <div className="text-lg font-semibold select-none text-gray-900">
              JChatMind
            </div>
          </div>
          <Input
            value={userId}
            size="small"
            prefix={<IdcardOutlined className="text-slate-400" />}
            onChange={(event) => setUserId(event.target.value)}
            placeholder="输入当前 userId"
          />
        </div>
      </div>
      <div className="flex-1 min-h-0 flex flex-col pt-3">
        <Tabs activeKey={activeKey} onChange={handleTabChange} items={items} />
      </div>
      <AddAgentModal
        open={isAddAgentModalOpen}
        onClose={toggleAddAgentModal}
        createAgentHandle={createAgentHandle}
        updateAgentHandle={updateAgentHandle}
        editingAgent={editingAgent}
      />
      <AddKnowledgeBaseModal
        open={isAddKnowledgeBaseModalOpen}
        onClose={toggleAddKnowledgeBaseModal}
        createKnowledgeBaseHandle={createKnowledgeBaseHandle}
      />
    </div>
  );
};

export default SideMenu;
