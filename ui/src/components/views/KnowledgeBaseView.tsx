import React, { useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import {
  Card,
  Typography,
  Button,
  Upload,
  Table,
  Popconfirm,
  Space,
  message,
  Empty,
  Progress,
  Tag,
  Tooltip,
} from "antd";
import {
  BookOutlined,
  UploadOutlined,
  DeleteOutlined,
  FileOutlined,
  CloseOutlined,
  RedoOutlined,
} from "@ant-design/icons";
import type { UploadProps } from "antd";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
import { useDocuments } from "../../hooks/useDocuments.ts";
import {
  cancelIngestionTask,
  getIngestionTask,
  retryIngestionTask,
  subscribeIngestionTaskProgress,
  uploadDocument,
  type DocumentVO,
  type IngestionTaskVO,
} from "../../api/api.ts";

const { Title, Text, Paragraph } = Typography;

const ACTIVE_INGESTION_STATUSES = new Set(["QUEUED", "RUNNING", "RETRYING"]);
const CANCELLABLE_INGESTION_STATUSES = new Set(["QUEUED", "RETRYING"]);
const RETRYABLE_INGESTION_STATUSES = new Set(["FAILED", "DEAD_LETTER"]);

function taskStatusText(status: IngestionTaskVO["status"]): string {
  return {
    QUEUED: "排队中",
    RUNNING: "处理中",
    RETRYING: "等待重试",
    FAILED: "处理失败",
    DEAD_LETTER: "重试已耗尽",
    CANCELLED: "已取消",
    SUCCEEDED: "处理完成",
  }[status];
}

function taskProgress(status: IngestionTaskVO["status"]): number {
  if (status === "SUCCEEDED") return 100;
  if (status === "FAILED" || status === "DEAD_LETTER" || status === "CANCELLED") return 100;
  if (status === "RUNNING" || status === "RETRYING") return 60;
  return 20;
}

function taskStatusColor(status: IngestionTaskVO["status"]): string {
  if (status === "SUCCEEDED") return "success";
  if (status === "FAILED" || status === "DEAD_LETTER") return "error";
  if (status === "CANCELLED") return "default";
  return "processing";
}

const KnowledgeBaseView: React.FC = () => {
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId?: string }>();
  const { knowledgeBases, refreshKnowledgeBases } = useKnowledgeBases();
  const { documents, loading, refreshDocuments, deleteDocument } =
    useDocuments(knowledgeBaseId);

  const [uploading, setUploading] = useState(false);
  const [ingestionTask, setIngestionTask] = useState<IngestionTaskVO | null>(null);
  const uploadIdempotencyKeys = useRef<Map<string, string>>(new Map());
  const currentIngestionTask = ingestionTask?.kbId === knowledgeBaseId ? ingestionTask : null;
  const ingestionTaskId = currentIngestionTask?.taskId;
  const ingestionTaskStatus = currentIngestionTask?.status;
  const canCancelIngestion = currentIngestionTask !== null
    && CANCELLABLE_INGESTION_STATUSES.has(currentIngestionTask.status);

  // 查找当前知识库的详细信息
  const currentKnowledgeBase = useMemo(() => {
    if (!knowledgeBaseId) return null;
    return (
      knowledgeBases.find((kb) => kb.knowledgeBaseId === knowledgeBaseId) ||
      null
    );
  }, [knowledgeBaseId, knowledgeBases]);

  useEffect(() => {
    setIngestionTask((currentTask) => currentTask?.kbId === knowledgeBaseId ? currentTask : null);
  }, [knowledgeBaseId]);

  useEffect(() => {
    if (knowledgeBaseId) {
      void refreshKnowledgeBases();
    }
  }, [knowledgeBaseId, refreshKnowledgeBases]);

  useEffect(() => {
    if (!knowledgeBaseId
      || !ingestionTaskId
      || !ingestionTaskStatus
      || !ACTIVE_INGESTION_STATUSES.has(ingestionTaskStatus)) {
      return;
    }

    let active = true;
    const controller = new AbortController();
    let lastEventId: number | undefined;
    const applyTaskUpdate = (nextTask: IngestionTaskVO) => {
      if (!active || nextTask.kbId !== knowledgeBaseId) {
        return;
      }
      setIngestionTask(nextTask);
      if (!ACTIVE_INGESTION_STATUSES.has(nextTask.status)) {
        void refreshDocuments();
        controller.abort();
      }
    };

    const refreshTask = async () => {
      try {
        const nextTask = await getIngestionTask(ingestionTaskId);
        if (!active) return;
        if (nextTask.kbId !== knowledgeBaseId) {
          setIngestionTask(null);
          return;
        }
        applyTaskUpdate(nextTask);
      } catch {
        return;
      }
    };

    void refreshTask();
    const connectProgress = async () => {
      while (active && !controller.signal.aborted) {
        try {
          await subscribeIngestionTaskProgress(
            ingestionTaskId,
            (event) => {
              if (event.sequence !== undefined) {
                if (lastEventId !== undefined && event.sequence <= lastEventId) {
                  return;
                }
                lastEventId = Math.max(lastEventId ?? 0, event.sequence);
              }
              applyTaskUpdate(event);
            },
            controller.signal,
            lastEventId,
          );
          if (active && !controller.signal.aborted) {
            await new Promise((resolve) => window.setTimeout(resolve, 1000));
          }
        } catch (error: unknown) {
          if (!active || controller.signal.aborted) {
            return;
          }
          console.error("摄入任务 SSE 连接失败:", error);
          await new Promise((resolve) => window.setTimeout(resolve, 1000));
        }
      }
    };
    void connectProgress();
    const timer = window.setInterval(() => {
      void refreshTask();
    }, 2000);

    return () => {
      active = false;
      controller.abort();
      window.clearInterval(timer);
    };
  }, [knowledgeBaseId, ingestionTaskId, ingestionTaskStatus, refreshDocuments]);

  // 处理文件上传
  const handleUpload: UploadProps["customRequest"] = async (options) => {
    const { file, onSuccess, onError } = options;

    if (!knowledgeBaseId) {
      message.error("请先选择知识库");
      return;
    }

    setUploading(true);
    const uploadFile = file as File;
    const uploadKey = `${uploadFile.name}:${uploadFile.size}:${uploadFile.lastModified}`;
    const idempotencyKey = uploadIdempotencyKeys.current.get(uploadKey) ?? crypto.randomUUID();
    uploadIdempotencyKeys.current.set(uploadKey, idempotencyKey);

    try {
      const response = await uploadDocument(knowledgeBaseId, file as File, idempotencyKey);
      if (response.taskId) {
        setIngestionTask({
          taskId: response.taskId,
          kbId: knowledgeBaseId,
          documentId: response.documentId,
          taskType: "DOCUMENT_INGESTION",
          status: "QUEUED",
          attemptCount: 0,
          maxAttempts: 3,
          errorSummary: null,
          createdAt: null,
          updatedAt: null,
          startedAt: null,
          completedAt: null,
        });
      }
      message.success(response.taskId ? "文档已上传，正在处理" : "文档上传成功");
      await refreshDocuments();
      onSuccess?.(file);
      uploadIdempotencyKeys.current.delete(uploadKey);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "上传失败");
      onError?.(error as Error);
    } finally {
      setUploading(false);
    }
  };

  const handleCancelIngestion = async () => {
    if (!currentIngestionTask || !canCancelIngestion) return;
    try {
      await cancelIngestionTask(currentIngestionTask.taskId);
      setIngestionTask({ ...currentIngestionTask, status: "CANCELLED" });
      message.success("已取消文档处理");
    } catch (error) {
      message.error(error instanceof Error ? error.message : "取消文档处理失败");
    }
  };

  const handleRetryIngestion = async () => {
    if (!currentIngestionTask || !RETRYABLE_INGESTION_STATUSES.has(currentIngestionTask.status)) return;
    try {
      await retryIngestionTask(currentIngestionTask.taskId);
      setIngestionTask({
        ...currentIngestionTask,
        status: "QUEUED",
        attemptCount: 0,
        errorSummary: null,
      });
      message.success("已重新提交文档处理");
    } catch (error) {
      message.error(error instanceof Error ? error.message : "重新提交文档处理失败");
    }
  };

  // 格式化文件大小
  const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + " " + sizes[i];
  };

  // 表格列定义
  const columns = [
    {
      title: "文件名",
      dataIndex: "filename",
      key: "filename",
      render: (text: string) => (
        <Space>
          <FileOutlined />
          <span>{text}</span>
        </Space>
      ),
    },
    {
      title: "类型",
      dataIndex: "filetype",
      key: "filetype",
      width: 120,
    },
    {
      title: "大小",
      dataIndex: "size",
      key: "size",
      width: 120,
      render: (size: number) => formatFileSize(size),
    },
    {
      title: "操作",
      key: "action",
      width: 100,
      render: (_: unknown, record: DocumentVO) => (
        <Popconfirm
          title="确定要删除这个文档吗？"
          description="删除后将无法恢复"
          onConfirm={() => deleteDocument(record.id)}
          okText="确定"
          cancelText="取消"
        >
          <Button type="text" danger icon={<DeleteOutlined />} size="small">
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  // 未选择知识库时的提示
  if (!knowledgeBaseId) {
    return (
      <div className="app-page-empty">
        <Empty
          image={<BookOutlined className="text-6xl text-gray-300" />}
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                未选择知识库
              </Title>
              <Text type="secondary" className="text-sm">
                请从左侧知识库列表中选择一个知识库查看详情
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  // 知识库不存在
  if (!currentKnowledgeBase) {
    return (
      <div className="app-page-empty">
        <Empty
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                知识库不存在
              </Title>
              <Text type="secondary" className="text-sm">
                请检查知识库 ID 是否正确
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  // 显示知识库详情和文档列表
  return (
    <div className="app-management-page">
      <div className="app-management-page__inner">
        <div className="mb-3">
          <Card>
            <div className="flex items-start gap-4">
              <div className="app-management-icon w-16 h-16 rounded-lg flex items-center justify-center text-3xl shrink-0">
                <BookOutlined />
              </div>
              <div className="flex-1">
                <Title level={3} className="mb-2">
                  {currentKnowledgeBase.name}
                </Title>
                {currentKnowledgeBase.description && (
                  <Paragraph className="text-gray-600 mb-0">
                    {currentKnowledgeBase.description}
                  </Paragraph>
                )}
                <Text type="secondary" className="text-sm">
                  知识库 ID: {currentKnowledgeBase.knowledgeBaseId}
                </Text>
              </div>
            </div>
          </Card>
        </div>
        {/* 知识库信息卡片 */}

        <div className="mb-3">
          {/* 上传文档区域 */}
          <Card title="上传文档">
            <Upload
              customRequest={handleUpload}
              showUploadList={false}
            accept=".md,.markdown,.txt,.html,.pdf"
              disabled={uploading}
            >
              <Button
                type="primary"
                icon={<UploadOutlined />}
                loading={uploading}
                size="large"
              >
                选择文件上传
              </Button>
            </Upload>
            <Text type="secondary" className="block mt-2 text-xs">
              支持格式: Markdown、纯文本、HTML 和 PDF
            </Text>
            {currentIngestionTask && (
              <div className="mt-3 border-t pt-3">
                <Space className="w-full justify-between" align="center">
                  <Space size="small">
                    <Text>{taskStatusText(currentIngestionTask.status)}</Text>
                    <Tag color={taskStatusColor(currentIngestionTask.status)}>
                      {currentIngestionTask.status}
                    </Tag>
                  </Space>
                  <Space size="small">
                    {canCancelIngestion && (
                      <Tooltip title="取消处理">
                        <Button
                          type="text"
                          size="small"
                          danger
                          icon={<CloseOutlined />}
                          aria-label="取消处理"
                          onClick={() => void handleCancelIngestion()}
                        />
                      </Tooltip>
                    )}
                    {RETRYABLE_INGESTION_STATUSES.has(currentIngestionTask.status) && (
                      <Tooltip title="重新处理">
                        <Button
                          type="text"
                          size="small"
                          icon={<RedoOutlined />}
                          aria-label="重新处理"
                          onClick={() => void handleRetryIngestion()}
                        />
                      </Tooltip>
                    )}
                  </Space>
                </Space>
                <Progress
                  percent={taskProgress(currentIngestionTask.status)}
                  status={
                    currentIngestionTask.status === "FAILED" || currentIngestionTask.status === "DEAD_LETTER"
                      ? "exception"
                      : currentIngestionTask.status === "CANCELLED"
                        ? "normal"
                        : currentIngestionTask.status === "SUCCEEDED"
                          ? "success"
                          : "active"
                  }
                  showInfo={false}
                  className="mt-2 mb-0"
                />
                {currentIngestionTask.errorSummary && (
                  <Text type="danger" className="block mt-1 text-xs">
                    {currentIngestionTask.errorSummary}
                  </Text>
                )}
              </div>
            )}
          </Card>
        </div>

        <div className="mb-3">
          {/* 文档列表 */}
          <Card title={`文档列表 (${documents.length})`}>
            {loading ? (
              <div className="text-center py-8">
                <Text type="secondary">加载中...</Text>
              </div>
            ) : documents.length === 0 ? (
              <Empty
                description={<Text type="secondary">暂无文档，请上传文档</Text>}
              />
            ) : (
              <Table
                columns={columns}
                dataSource={documents}
                rowKey="id"
                pagination={{
                  pageSize: 10,
                  // showSizeChanger: true,
                  showTotal: (total) => `共 ${total} 条`,
                }}
              />
            )}
          </Card>
        </div>
      </div>
    </div>
  );
};

export default KnowledgeBaseView;
