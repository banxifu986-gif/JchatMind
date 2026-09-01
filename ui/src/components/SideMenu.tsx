import React, { useMemo, useState } from "react";
import {
  Avatar,
  Button,
  Dropdown,
  Input,
  Modal,
  Popconfirm,
  Tooltip,
  message,
} from "antd";
import {
  BookOutlined,
  BulbOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MessageOutlined,
  MoreOutlined,
  PlusOutlined,
  RobotOutlined,
  SearchOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { useLocation, useNavigate } from "react-router-dom";
import type { AgentVO, ChatSessionVO } from "../api/api.ts";
import type { KnowledgeBase } from "../types";
import { useAgents } from "../hooks/useAgents.ts";
import { useChatSessions } from "../hooks/useChatSessions.ts";
import { useKnowledgeBases } from "../hooks/useKnowledgeBases.ts";
import { useUser } from "../hooks/useUser.ts";
import { getAgentAvatar, getKnowledgeBaseEmoji } from "../utils";
import AddAgentModal from "./modals/AddAgentModal.tsx";
import AddKnowledgeBaseModal from "./modals/AddKnowledgeBaseModal.tsx";
import { LoginModal } from "./auth/LoginModal.tsx";
import { RegisterModal } from "./auth/RegisterModal.tsx";

interface SideMenuProps {
  collapsed?: boolean;
  onToggleCollapsed?: () => void;
}

const getSessionTitle = (
  session: ChatSessionVO,
  agentMap: Map<string, string>,
) => {
  if (session.title?.trim()) {
    return session.title;
  }
  const agentName = agentMap.get(session.agentId);
  return agentName ? `${agentName} 的对话` : "新对话";
};

const SideMenu: React.FC<SideMenuProps> = ({
  collapsed = false,
  onToggleCollapsed,
}) => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, isLogin, logout } = useUser();
  const { agents, createAgentHandle, deleteAgentHandle, updateAgentHandle } =
    useAgents();
  const {
    chatSessions,
    loading: chatSessionsLoading,
    deleteChatSession,
  } = useChatSessions();
  const {
    knowledgeBases,
    createKnowledgeBaseHandle,
    deleteKnowledgeBaseHandle,
  } = useKnowledgeBases();

  const [loginOpen, setLoginOpen] = useState(false);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [agentsOpen, setAgentsOpen] = useState(true);
  const [knowledgeBasesOpen, setKnowledgeBasesOpen] = useState(false);
  const [isAddAgentModalOpen, setIsAddAgentModalOpen] = useState(false);
  const [editingAgent, setEditingAgent] = useState<AgentVO | null>(null);
  const [isAddKnowledgeBaseModalOpen, setIsAddKnowledgeBaseModalOpen] =
    useState(false);

  const agentMap = useMemo(() => {
    return new Map(agents.map((agent) => [agent.id, agent.name]));
  }, [agents]);

  const filteredSessions = useMemo(() => {
    const normalizedQuery = searchQuery.trim().toLowerCase();
    if (!normalizedQuery) {
      return chatSessions;
    }
    return chatSessions.filter((session) =>
      getSessionTitle(session, agentMap).toLowerCase().includes(normalizedQuery),
    );
  }, [agentMap, chatSessions, searchQuery]);

  const chatSessionId = location.pathname.startsWith("/chat/")
    ? location.pathname.slice("/chat/".length).split("/")[0]
    : null;

  const openNewChat = () => {
    navigate("/chat");
  };

  const openAgent = (agentId: string) => {
    navigate("/chat", { state: { selectedAgentId: agentId } });
  };

  const onDeleteKnowledgeBase = async (knowledgeBaseId: string) => {
    try {
      await deleteKnowledgeBaseHandle(knowledgeBaseId);
      if (location.pathname === `/knowledge-base/${knowledgeBaseId}`) {
        navigate("/knowledge-base");
      }
      message.success("知识库删除完成");
    } catch (error) {
      message.error(error instanceof Error ? error.message : "知识库删除失败");
    }
  };

  const startEditingAgent = (agent: AgentVO) => {
    setEditingAgent(agent);
    setIsAddAgentModalOpen(true);
  };

  const confirmDeleteAgent = (agent: AgentVO) => {
    Modal.confirm({
      title: "确定要删除这个智能体吗？",
      content: "删除后将无法恢复",
      okText: "确定",
      cancelText: "取消",
      okType: "danger",
      onOk: () => deleteAgentHandle(agent.id),
    });
  };

  const closeAgentModal = () => {
    setIsAddAgentModalOpen(false);
    setEditingAgent(null);
  };

  const closeKnowledgeBaseModal = () => {
    setIsAddKnowledgeBaseModalOpen(false);
  };

  const isRouteActive = (path: string) => location.pathname.startsWith(path);
  const isChatWorkspaceRoute =
    location.pathname === "/"
    || location.pathname === "/agent"
    || location.pathname.startsWith("/chat");

  const handleAgentsClick = () => {
    setAgentsOpen((previous) => !previous);
    if (!isChatWorkspaceRoute) {
      navigate("/agent");
    }
  };

  return (
    <div className="app-sidebar-menu">
      <header className="app-sidebar__header">
        <div className="app-sidebar__brand">
          <span className="app-sidebar__brand-mark" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          <span className="app-sidebar__brand-name sidebar-label">JChatMind</span>
        </div>
        <div className="app-sidebar__header-actions">
          <Tooltip title={searchOpen ? "关闭搜索" : "搜索会话"}>
            <Button
              type="text"
              size="small"
              aria-label={searchOpen ? "关闭搜索" : "搜索会话"}
              icon={<SearchOutlined />}
              onClick={() => setSearchOpen((previous) => !previous)}
            />
          </Tooltip>
          <Tooltip title={collapsed ? "展开侧栏" : "收起侧栏"}>
            <Button
              type="text"
              size="small"
              aria-label={collapsed ? "展开侧栏" : "收起侧栏"}
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={onToggleCollapsed}
            />
          </Tooltip>
        </div>
      </header>

      <div className="app-sidebar__body">
        {searchOpen && !collapsed && (
          <div className="app-sidebar__search">
            <Input
              allowClear
              autoFocus
              prefix={<SearchOutlined />}
              placeholder="搜索最近会话"
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
            />
          </div>
        )}

        <Tooltip title={collapsed ? "新建对话" : undefined} placement="right">
          <Button
            type="primary"
            block
            icon={<PlusOutlined />}
            onClick={openNewChat}
            className="app-sidebar__new-chat"
          >
            <span className="sidebar-label">新建对话</span>
          </Button>
        </Tooltip>

        <section className="app-sidebar__section app-sidebar__sessions">
          <div className="app-sidebar__section-title sidebar-label">
            <span>最近对话</span>
            <span className="app-sidebar__section-count">
              {chatSessions.length}
            </span>
          </div>
          <div className="app-sidebar__session-list">
            {chatSessionsLoading ? (
              <div className="app-sidebar__empty sidebar-label">加载中...</div>
            ) : filteredSessions.length === 0 ? (
              <div className="app-sidebar__empty sidebar-label">
                {searchQuery ? "没有匹配的会话" : "还没有聊天记录"}
              </div>
            ) : (
              filteredSessions.map((session) => {
                const isActive = session.id === chatSessionId;
                return (
                  <div
                    className={`app-sidebar__session ${isActive ? "is-active" : ""}`}
                    key={session.id}
                    onClick={() => navigate(`/chat/${session.id}`)}
                    title={getSessionTitle(session, agentMap)}
                  >
                    <MessageOutlined className="app-sidebar__session-icon" />
                    <span className="app-sidebar__session-title sidebar-label">
                      {getSessionTitle(session, agentMap)}
                    </span>
                    <Popconfirm
                      title="确认删除这条聊天记录？"
                      description="删除后无法恢复"
                      okText="确认"
                      cancelText="取消"
                      onConfirm={() => deleteChatSession(session.id)}
                    >
                      <Button
                        type="text"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        aria-label={`删除会话 ${getSessionTitle(session, agentMap)}`}
                        className="app-sidebar__session-delete"
                        onClick={(event) => event.stopPropagation()}
                      />
                    </Popconfirm>
                  </div>
                );
              })
            )}
          </div>
        </section>

        <section className="app-sidebar__section app-sidebar__workspace">
          <div className="app-sidebar__section-title sidebar-label">工作区</div>

          <div className="app-sidebar__group">
            <button
              type="button"
              className={`app-sidebar__nav-item ${isChatWorkspaceRoute ? "is-active" : ""}`}
              onClick={handleAgentsClick}
              title="智能体"
            >
              <RobotOutlined />
              <span className="sidebar-label">智能体</span>
              <DownOutlined
                className={`app-sidebar__group-chevron ${agentsOpen ? "is-open" : ""}`}
              />
            </button>
            {agentsOpen && !collapsed && (
              <div className="app-sidebar__nested-list">
                {agents.length === 0 ? (
                  <span className="app-sidebar__nested-empty">暂无智能体</span>
                ) : (
                  agents.map((agent) => {
                    const avatar = getAgentAvatar(agent.id, agent.name);
                    return (
                      <div
                        className="app-sidebar__agent"
                        key={agent.id}
                        onClick={() => openAgent(agent.id)}
                        title={agent.description || agent.name}
                      >
                        <span
                          className={`app-sidebar__agent-avatar bg-gradient-to-br ${avatar.gradientClass}`}
                        >
                          {avatar.initial}
                        </span>
                        <span className="app-sidebar__agent-name">{agent.name}</span>
                        <Dropdown
                          trigger={["click"]}
                          menu={{
                            items: [
                              {
                                key: "edit",
                                icon: <EditOutlined />,
                                label: "编辑",
                                onClick: () => startEditingAgent(agent),
                              },
                              {
                                key: "delete",
                                danger: true,
                                icon: <DeleteOutlined />,
                                label: "删除",
                                onClick: () => confirmDeleteAgent(agent),
                              },
                            ],
                          }}
                        >
                          <Button
                            type="text"
                            size="small"
                            icon={<MoreOutlined />}
                            aria-label={`管理智能体 ${agent.name}`}
                            className="app-sidebar__item-action"
                            onClick={(event) => event.stopPropagation()}
                          />
                        </Dropdown>
                      </div>
                    );
                  })
                )}
                <button
                  type="button"
                  className="app-sidebar__nested-create"
                  onClick={() => setIsAddAgentModalOpen(true)}
                >
                  <PlusOutlined />
                  添加智能体
                </button>
              </div>
            )}
          </div>

          <div className="app-sidebar__group">
            <button
              type="button"
              className={`app-sidebar__nav-item ${isRouteActive("/knowledge-base") ? "is-active" : ""}`}
              onClick={() => {
                setKnowledgeBasesOpen((previous) => !previous);
                if (!isRouteActive("/knowledge-base")) {
                  navigate("/knowledge-base");
                }
              }}
              title="知识库"
            >
              <BookOutlined />
              <span className="sidebar-label">知识库</span>
              <DownOutlined
                className={`app-sidebar__group-chevron ${knowledgeBasesOpen ? "is-open" : ""}`}
              />
            </button>
            {knowledgeBasesOpen && !collapsed && (
              <div className="app-sidebar__nested-list">
                {knowledgeBases.map((knowledgeBase: KnowledgeBase) => (
                  <div
                    className="app-sidebar__knowledge-base"
                    key={knowledgeBase.knowledgeBaseId}
                    onClick={() => navigate(`/knowledge-base/${knowledgeBase.knowledgeBaseId}`)}
                    title={knowledgeBase.description || knowledgeBase.name}
                  >
                    <span className="app-sidebar__knowledge-base-icon">
                      {getKnowledgeBaseEmoji(knowledgeBase.knowledgeBaseId)}
                    </span>
                    <span className="app-sidebar__agent-name">{knowledgeBase.name}</span>
                    <Popconfirm
                      title="确定要删除这个知识库吗？"
                      description="删除后将异步清理文档和文件，无法恢复"
                      okText="确定"
                      cancelText="取消"
                      onConfirm={() => onDeleteKnowledgeBase(knowledgeBase.knowledgeBaseId)}
                    >
                      <Button
                        type="text"
                        danger
                        size="small"
                        icon={<DeleteOutlined />}
                        aria-label={`删除知识库 ${knowledgeBase.name}`}
                        className="app-sidebar__item-action"
                        onClick={(event) => event.stopPropagation()}
                      />
                    </Popconfirm>
                  </div>
                ))}
                <button
                  type="button"
                  className="app-sidebar__nested-create"
                  onClick={() => setIsAddKnowledgeBaseModalOpen(true)}
                >
                  <PlusOutlined />
                  新建知识库
                </button>
              </div>
            )}
          </div>

          <button
            type="button"
            className={`app-sidebar__nav-item ${isRouteActive("/user-memory") ? "is-active" : ""}`}
            onClick={() => navigate("/user-memory")}
            title="用户记忆"
          >
            <BulbOutlined />
            <span className="sidebar-label">用户记忆</span>
          </button>

        </section>
      </div>

      <footer className="app-sidebar__footer">
        {isLogin && user ? (
          <Dropdown
            placement="topLeft"
            menu={{
              items: [
                {
                  key: "logout",
                  label: "退出登录",
                  icon: <LogoutOutlined />,
                  onClick: logout,
                },
              ],
            }}
          >
            <button type="button" className="app-sidebar__account" title={user.username}>
              <Avatar size={30} src={user.avatarUrl}>
                {user.username.slice(0, 1).toUpperCase()}
              </Avatar>
              <span className="app-sidebar__account-copy sidebar-label">
                <strong>{user.username}</strong>
                <small>个人工作区</small>
              </span>
              <MoreOutlined className="app-sidebar__account-more" />
            </button>
          </Dropdown>
        ) : (
          <div className="app-sidebar__auth">
            <Avatar size={30} icon={<UserOutlined />} />
            <div className="app-sidebar__auth-actions sidebar-label">
              <Button type="link" size="small" onClick={() => setLoginOpen(true)}>
                登录
              </Button>
              <Button type="link" size="small" onClick={() => setRegisterOpen(true)}>
                注册
              </Button>
            </div>
          </div>
        )}
      </footer>

      <AddAgentModal
        open={isAddAgentModalOpen}
        onClose={closeAgentModal}
        createAgentHandle={createAgentHandle}
        updateAgentHandle={updateAgentHandle}
        editingAgent={editingAgent}
      />
      <AddKnowledgeBaseModal
        open={isAddKnowledgeBaseModalOpen}
        onClose={closeKnowledgeBaseModal}
        createKnowledgeBaseHandle={createKnowledgeBaseHandle}
      />
      <LoginModal
        open={loginOpen}
        onClose={() => setLoginOpen(false)}
        onSwitchToRegister={() => {
          setLoginOpen(false);
          setRegisterOpen(true);
        }}
      />
      <RegisterModal
        open={registerOpen}
        onClose={() => setRegisterOpen(false)}
        onSwitchToLogin={() => {
          setRegisterOpen(false);
          setLoginOpen(true);
        }}
      />
    </div>
  );
};

export default SideMenu;
