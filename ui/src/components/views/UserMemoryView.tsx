import React, { useCallback, useEffect, useState } from "react";
import { Button, Card, Empty, Popconfirm, Space, Tag, Typography } from "antd";
import {
  CheckOutlined,
  DeleteOutlined,
  InboxOutlined,
  SafetyOutlined,
} from "@ant-design/icons";
import {
  confirmUserMemoryCandidate,
  deleteUserMemory,
  getUserMemories,
  getUserMemoryCandidates,
  type UserMemoryCandidateVO,
  type UserMemoryVO,
} from "../../api/api.ts";
import { useUser } from "../../hooks/useUser.ts";

const { Title, Text, Paragraph } = Typography;

const UserMemoryView: React.FC = () => {
  const { userId } = useUser();
  const [loading, setLoading] = useState(false);
  const [memories, setMemories] = useState<UserMemoryVO[]>([]);
  const [candidates, setCandidates] = useState<UserMemoryCandidateVO[]>([]);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [memoriesResp, candidatesResp] = await Promise.all([
        getUserMemories(userId),
        getUserMemoryCandidates(userId),
      ]);
      setMemories(memoriesResp.memories);
      setCandidates(candidatesResp.candidates);
    } finally {
      setLoading(false);
    }
  }, [userId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleConfirm = async (candidateId: string) => {
    await confirmUserMemoryCandidate(userId, candidateId);
    await refresh();
  };

  const handleDeleteMemory = async (memoryId: string) => {
    await deleteUserMemory(userId, memoryId);
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
                当前 userId: <Text code>{userId}</Text>
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
                        <Button
                          type="primary"
                          icon={<CheckOutlined />}
                          onClick={() => handleConfirm(candidate.id)}
                        >
                          确认保存
                        </Button>
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
            extra={<Tag color="blue">{memories.length}</Tag>}
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
                      </div>
                    </Space>
                  </Card>
                ))}
              </div>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
};

export default UserMemoryView;
