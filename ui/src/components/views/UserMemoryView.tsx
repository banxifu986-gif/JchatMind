import React, { useCallback, useEffect, useState } from "react";
import { Button, Card, Collapse, Empty, Input, Modal, Popconfirm, Select, Space, Tag, Typography } from "antd";
import {
  CheckOutlined,
  ClockCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  InboxOutlined,
  SafetyOutlined,
} from "@ant-design/icons";
import {
  clearUserMemories,
  confirmUserMemoryConsolidationProposal,
  confirmUserMemoryCandidate,
  createUserMemoryConsolidationProposal,
  discardUserMemoryCandidate,
  discardUserMemoryConsolidationProposal,
  deleteUserMemory,
  getUserMemories,
  getUserMemoryAutomation,
  getUserMemoryCandidates,
  getUserMemoryConsolidationProposals,
  updateUserMemoryAutomation,
  type UserMemoryAutomationMode,
  type UserMemoryConsolidationProposalVO,
  type UserMemoryCandidateVO,
  type UserMemoryVO,
  updateUserMemory,
  updateUserMemoryExpiration,
} from "../../api/api.ts";
import { useUser } from "../../hooks/useUser.ts";

const { Title, Text, Paragraph } = Typography;

const DEFAULT_MEMORY_EXPIRATION_DAYS = 365;

const toExpirationInputValue = (expiresAt?: string) => {
  if (expiresAt) {
    return expiresAt.slice(0, 16);
  }
  const defaultExpiration = new Date();
  defaultExpiration.setDate(defaultExpiration.getDate() + DEFAULT_MEMORY_EXPIRATION_DAYS);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${defaultExpiration.getFullYear()}-${pad(defaultExpiration.getMonth() + 1)}-${pad(defaultExpiration.getDate())}T${pad(defaultExpiration.getHours())}:${pad(defaultExpiration.getMinutes())}`;
};

const formatExpiration = (expiresAt?: string) => {
  if (!expiresAt) {
    return "未设置（历史记录）";
  }
  return `有效至 ${expiresAt.replace("T", " ").slice(0, 16)}`;
};

const isExpired = (expiresAt?: string) => {
  return Boolean(expiresAt && new Date(expiresAt).getTime() <= Date.now());
};

const UserMemoryView: React.FC = () => {
  const { user } = useUser();
  const [loading, setLoading] = useState(false);
  const [memories, setMemories] = useState<UserMemoryVO[]>([]);
  const [candidates, setCandidates] = useState<UserMemoryCandidateVO[]>([]);
  const [automationMode, setAutomationMode] = useState<UserMemoryAutomationMode>("BALANCED");
  const [consolidationProposals, setConsolidationProposals] = useState<UserMemoryConsolidationProposalVO[]>([]);
  const [editingMemory, setEditingMemory] = useState<UserMemoryVO | null>(null);
  const [editingContent, setEditingContent] = useState("");
  const [editingExpirationMemory, setEditingExpirationMemory] = useState<UserMemoryVO | null>(null);
  const [editingExpiration, setEditingExpiration] = useState("");
  const activeMemoryCount = memories.filter(
    (memory) => !memory.supersededByMemoryId && !isExpired(memory.expiresAt),
  ).length;

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [memoriesResp, candidatesResp, automationResp, proposalsResp] = await Promise.all([
        getUserMemories(),
        getUserMemoryCandidates(),
        getUserMemoryAutomation(),
        getUserMemoryConsolidationProposals(),
      ]);
      setMemories(memoriesResp.memories);
      setCandidates(candidatesResp.candidates);
      setAutomationMode(automationResp.mode);
      setConsolidationProposals(proposalsResp.proposals);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleConfirm = async (candidateId: string) => {
    await confirmUserMemoryCandidate(candidateId);
    await refresh();
  };

  const handleDiscardCandidate = async (candidateId: string) => {
    await discardUserMemoryCandidate(candidateId);
    await refresh();
  };

  const handleDeleteMemory = async (memoryId: string) => {
    await deleteUserMemory(memoryId);
    await refresh();
  };

  const handleStartEditMemory = (memory: UserMemoryVO) => {
    setEditingMemory(memory);
    setEditingContent(memory.content);
  };

  const handleEditMemory = async () => {
    if (!editingMemory) {
      return;
    }
    await updateUserMemory(editingMemory.id, { content: editingContent });
    setEditingMemory(null);
    setEditingContent("");
    await refresh();
  };

  const handleClearMemories = async () => {
    await clearUserMemories();
    await refresh();
  };

  const handleAutomationModeChange = async (mode: UserMemoryAutomationMode) => {
    await updateUserMemoryAutomation(mode);
    setAutomationMode(mode);
  };

  const handleCreateConsolidationProposal = async () => {
    await createUserMemoryConsolidationProposal();
    await refresh();
  };

  const handleConfirmConsolidationProposal = async (proposalId: string) => {
    await confirmUserMemoryConsolidationProposal(proposalId);
    await refresh();
  };

  const handleDiscardConsolidationProposal = async (proposalId: string) => {
    await discardUserMemoryConsolidationProposal(proposalId);
    await refresh();
  };

  const handleStartEditExpiration = (memory: UserMemoryVO) => {
    setEditingExpirationMemory(memory);
    setEditingExpiration(toExpirationInputValue(memory.expiresAt));
  };

  const handleEditExpiration = async () => {
    if (!editingExpirationMemory) {
      return;
    }
    await updateUserMemoryExpiration(editingExpirationMemory.id, {
      expiresAt: editingExpiration,
    });
    setEditingExpirationMemory(null);
    setEditingExpiration("");
    await refresh();
  };

  return (
    <div className="app-memory-page">
      <div className="app-management-page__inner app-memory-page__inner">
        <Card className="app-memory-hero">
          <Space align="start" size="large">
            <div className="w-14 h-14 rounded-2xl bg-orange-500 text-white flex items-center justify-center text-2xl">
              <SafetyOutlined />
            </div>
            <div>
              <Title level={3} className="!mb-1">
                用户记忆管理
              </Title>
              <Paragraph className="!mb-1 text-slate-600">
                当前用户: <Text code>{user?.username || "未登录"}</Text>
              </Paragraph>
              <Text type="secondary">
                默认平衡模式会自动保存明确、低风险的偏好；其他候选需确认。一次性要求不会进入长期记忆。
              </Text>
              <div className="mt-3 flex items-center gap-3">
                <Text>自动化程度</Text>
                <Select<UserMemoryAutomationMode>
                  value={automationMode}
                  onChange={(mode) => void handleAutomationModeChange(mode)}
                  options={[
                    { value: "REVIEW_ALL", label: "全部确认" },
                    { value: "BALANCED", label: "平衡（推荐）" },
                    { value: "AUTOMATIC", label: "更多自动保存" },
                  ]}
                />
              </div>
              <Text type="secondary" className="block mt-2">
                {automationMode === "REVIEW_ALL" && "所有持久记忆都会先进入待确认列表。"}
                {automationMode === "BALANCED" && "仅明确、低风险的高置信偏好会自动保存；其他记忆先供你确认。"}
                {automationMode === "AUTOMATIC" && "高置信的偏好和非敏感背景可自动保存；目标、事实、约束、敏感或关键内容仍需确认。"}
              </Text>
              {activeMemoryCount >= 10 && (
                <Button className="mt-3" onClick={() => void handleCreateConsolidationProposal()}>
                  整理建议（不会自动应用）
                </Button>
              )}
            </div>
          </Space>
        </Card>

        <div className="app-memory-grid grid grid-cols-1 lg:grid-cols-2 gap-6">
          <Card
            title="待确认候选"
            loading={loading}
            className="border-0 shadow-sm"
            extra={<Tag color="orange">{candidates.length}</Tag>}
          >
            {candidates.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="暂无待确认候选记忆"
              />
            ) : (
              <div className="space-y-3">
                {candidates.map((candidate) => (
                  <Card key={candidate.id} size="small" className="bg-amber-50/60 border-amber-200">
                    <Space direction="vertical" size="small" className="w-full">
                      <Space>
                        <Tag color="gold">{candidate.memoryType}</Tag>
                        {candidate.sessionId && <Text type="secondary">会话 {candidate.sessionId}</Text>}
                      </Space>
                      <Paragraph className="!mb-0">{candidate.content}</Paragraph>
                      {candidate.evidence && (
                        <Text type="secondary">线索: {candidate.evidence}</Text>
                      )}
                      <div className="flex justify-end">
                        <Space>
                          <Popconfirm
                            title="确认忽略此候选记忆？"
                            onConfirm={() => handleDiscardCandidate(candidate.id)}
                            okText="忽略"
                            cancelText="取消"
                          >
                            <Button danger icon={<DeleteOutlined />}>
                              忽略
                            </Button>
                          </Popconfirm>
                          <Button
                            type="primary"
                            icon={<CheckOutlined />}
                            onClick={() => handleConfirm(candidate.id)}
                          >
                            确认保存
                          </Button>
                        </Space>
                      </div>
                    </Space>
                  </Card>
                ))}
              </div>
            )}
          </Card>

          <Card
            title="已确认长期记忆"
            loading={loading}
            className="border-0 shadow-sm"
            extra={
              <Space size="small">
                <Tag color="blue">{memories.length}</Tag>
                {memories.length > 0 && (
                  <Popconfirm
                    title="确认清空全部长期记忆？"
                    onConfirm={handleClearMemories}
                    okText="清空"
                    cancelText="取消"
                  >
                    <Button danger size="small" icon={<DeleteOutlined />}>
                      清空
                    </Button>
                  </Popconfirm>
                )}
              </Space>
            }
          >
            {memories.length === 0 ? (
              <Empty image={<InboxOutlined className="text-5xl text-slate-300" />} description="暂无已保存记忆" />
            ) : (
              <div className="space-y-3">
                {memories.map((memory) => (
                  <Card key={memory.id} size="small" className="bg-white border-slate-200">
                    <Space direction="vertical" size="small" className="w-full">
                      <Space>
                        <Tag color="blue">{memory.memoryType}</Tag>
                        {memory.sessionId && <Text type="secondary">会话 {memory.sessionId}</Text>}
                        {memory.supersededByMemoryId && <Tag>已被整理替代</Tag>}
                      </Space>
                      <Paragraph className="!mb-0">{memory.content}</Paragraph>
                      {(memory.evidenceText || memory.sessionId) && (
                        <Collapse
                          size="small"
                          items={[{
                            key: "source",
                            label: "来源证据",
                            children: (
                              <Space direction="vertical" size="small">
                                {memory.evidenceText && <Text>{memory.evidenceText}</Text>}
                                {memory.sessionId && (
                                  <a href={`/chat/${memory.sessionId}`}>查看来源会话</a>
                                )}
                              </Space>
                            ),
                          }]}
                        />
                      )}
                      <Space size="small">
                        <Text type="secondary">有效期: {formatExpiration(memory.expiresAt)}</Text>
                        {isExpired(memory.expiresAt) && <Tag>已过期</Tag>}
                      </Space>
                      <div className="flex justify-end">
                        <Popconfirm
                          title="确认删除这条长期记忆？"
                          onConfirm={() => handleDeleteMemory(memory.id)}
                          okText="删除"
                          cancelText="取消"
                        >
                          <Button danger icon={<DeleteOutlined />}>
                            删除
                          </Button>
                        </Popconfirm>
                        <Button icon={<ClockCircleOutlined />} onClick={() => handleStartEditExpiration(memory)}>
                          有效期
                        </Button>
                        <Button icon={<EditOutlined />} onClick={() => handleStartEditMemory(memory)}>
                          编辑
                        </Button>
                      </div>
                    </Space>
                  </Card>
                ))}
              </div>
            )}
          </Card>
        </div>
        {consolidationProposals.length > 0 && (
          <Card title="待审核整理建议" className="mt-6 border-0 shadow-sm">
            <div className="space-y-3">
              {consolidationProposals.map((proposal) => (
                <Card key={proposal.id} size="small">
                  <Space direction="vertical" className="w-full">
                    <Tag color="blue">{proposal.proposedType} · 合并 {proposal.sourceMemoryIds.length} 条</Tag>
                    <Paragraph className="!mb-0">{proposal.proposedContent}</Paragraph>
                    <div className="rounded bg-slate-50 p-3">
                      <Text type="secondary">来源记忆</Text>
                      <ul className="mb-0 mt-2 list-disc pl-5">
                        {proposal.sourceMemoryIds.map((sourceId) => {
                          const source = memories.find((memory) => memory.id === sourceId);
                          return <li key={sourceId}>{source?.content || "来源已变更"}</li>;
                        })}
                      </ul>
                    </div>
                    <Text type="secondary">确认后才会创建新记忆并标记来源记忆为已替代。</Text>
                    <div className="flex justify-end">
                      <Space>
                        <Button danger onClick={() => void handleDiscardConsolidationProposal(proposal.id)}>
                          丢弃建议
                        </Button>
                        <Button type="primary" onClick={() => void handleConfirmConsolidationProposal(proposal.id)}>
                          确认整理
                        </Button>
                      </Space>
                    </div>
                  </Space>
                </Card>
              ))}
            </div>
          </Card>
        )}
        <Modal
          title="编辑长期记忆"
          open={editingMemory !== null}
          onCancel={() => {
            setEditingMemory(null);
            setEditingContent("");
          }}
          onOk={handleEditMemory}
          okText="保存"
          cancelText="取消"
          okButtonProps={{ disabled: !editingContent.trim() }}
          destroyOnClose
        >
          <Input.TextArea
            autoFocus
            value={editingContent}
            onChange={(event) => setEditingContent(event.target.value)}
            autoSize={{ minRows: 3, maxRows: 8 }}
          />
        </Modal>
        <Modal
          title="设置记忆有效期"
          open={editingExpirationMemory !== null}
          onCancel={() => {
            setEditingExpirationMemory(null);
            setEditingExpiration("");
          }}
          onOk={handleEditExpiration}
          okText="保存"
          cancelText="取消"
          okButtonProps={{ disabled: !editingExpiration }}
          destroyOnClose
        >
          <Space direction="vertical" size="small" className="w-full">
            <Text type="secondary">默认有效期为 365 天；设置的时间必须晚于当前时间。</Text>
            <Input
              type="datetime-local"
              value={editingExpiration}
              onChange={(event) => setEditingExpiration(event.target.value)}
            />
          </Space>
        </Modal>
      </div>
    </div>
  );
};

export default UserMemoryView;
