import React, { useCallback, useEffect, useState } from "react";
import { Button, Card, Empty, Input, Modal, Popconfirm, Space, Tag, Typography } from "antd";
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
  confirmUserMemoryCandidate,
  discardUserMemoryCandidate,
  deleteUserMemory,
  getUserMemories,
  getUserMemoryCandidates,
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
  const [editingMemory, setEditingMemory] = useState<UserMemoryVO | null>(null);
  const [editingContent, setEditingContent] = useState("");
  const [editingExpirationMemory, setEditingExpirationMemory] = useState<UserMemoryVO | null>(null);
  const [editingExpiration, setEditingExpiration] = useState("");

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [memoriesResp, candidatesResp] = await Promise.all([
        getUserMemories(),
        getUserMemoryCandidates(),
      ]);
      setMemories(memoriesResp.memories);
      setCandidates(candidatesResp.candidates);
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
    <div className="h-full overflow-y-auto bg-slate-100/60">
      <div className="max-w-5xl mx-auto p-6 space-y-6">
        <Card className="border-0 shadow-sm bg-gradient-to-r from-amber-50 to-orange-50">
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
                候选记忆需手动确认后才会进入 Agent 长期上下文。
              </Text>
            </div>
          </Space>
        </Card>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
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
                      </Space>
                      <Paragraph className="!mb-0">{memory.content}</Paragraph>
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
