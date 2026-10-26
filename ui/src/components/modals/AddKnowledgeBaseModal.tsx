import React, { useState } from "react";
import { Button, Input, message, Modal } from "antd";
import TextArea from "antd/es/input/TextArea";
import { SaveOutlined } from "@ant-design/icons";
import { type CreateKnowledgeBaseRequest } from "../../api/api.ts";

interface AddKnowledgeBaseModalProps {
  open: boolean;
  onClose: () => void;
  createKnowledgeBaseHandle: (
    request: CreateKnowledgeBaseRequest,
  ) => Promise<void>;
}

const AddKnowledgeBaseModal: React.FC<AddKnowledgeBaseModalProps> = ({
  open,
  onClose,
  createKnowledgeBaseHandle,
}) => {
  const [formData, setFormData] = useState<CreateKnowledgeBaseRequest>({
    name: "",
    description: "",
  });
  
  const [createLoading, setCreateLoading] = useState(false);
  const [nameTouched, setNameTouched] = useState(false);
  
  const handleSubmit = async () => {
    if (!formData.name.trim()) {
      setNameTouched(true);
      return;
    }
    setCreateLoading(true);
    
    try {
      await createKnowledgeBaseHandle({
        name: formData.name.trim(),
        description: formData.description?.trim() ?? "",
      });
      // 重置表单
      setFormData({
        name: "",
        description: "",
      });
      onClose();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "知识库创建失败，请重试");
    } finally {
      setCreateLoading(false);
    }
  };

  const handleCancel = () => {
    if (createLoading) {
      return;
    }
    // 重置表单
    setFormData({
      name: "",
      description: "",
    });
    setNameTouched(false);
    onClose();
  };

  return (
    <Modal
      open={open}
      onCancel={handleCancel}
      closable={!createLoading}
      title="新建知识库"
      footer={null}
      width={600}
      centered
    >
      <div className="py-4">
        <div className="mb-4">
          <label className="block text-gray-700 font-medium mb-2">
            名称 <span className="text-red-500">*</span>
          </label>
          <Input
            placeholder="请输入知识库名称"
            value={formData.name}
            status={nameTouched && !formData.name.trim() ? "error" : undefined}
            onBlur={() => setNameTouched(true)}
            onChange={(e) =>
              setFormData({ ...formData, name: e.target.value })
            }
            onPressEnter={handleSubmit}
          />
          {nameTouched && !formData.name.trim() && (
            <div className="mt-1 text-xs text-red-500">请输入知识库名称</div>
          )}
        </div>
        <div className="mb-6">
          <label className="block text-gray-700 font-medium mb-2">
            描述
          </label>
          <TextArea
            placeholder="请输入知识库描述（可选）"
            rows={4}
            value={formData.description}
            onChange={(e) =>
              setFormData({ ...formData, description: e.target.value })
            }
          />
        </div>
        <div className="flex justify-end gap-2">
          <Button onClick={handleCancel} disabled={createLoading}>取消</Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={createLoading}
            onClick={handleSubmit}
            disabled={!formData.name.trim()}
          >
            创建
          </Button>
        </div>
      </div>
    </Modal>
  );
};

export default AddKnowledgeBaseModal;
