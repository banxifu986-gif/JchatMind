import { useState } from "react";
import { Modal, Form, Input, Button, message } from "antd";
import { registerUser } from "../../api/api.ts";
import type { RegisterResponse } from "../../api/api.ts";
import { useUser } from "../../hooks/useUser.ts";

interface Props {
  open: boolean;
  onClose: () => void;
  onSwitchToLogin: () => void;
}

export function RegisterModal({ open, onClose, onSwitchToLogin }: Props) {
  const { login } = useUser();
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (values: Record<string, string>) => {
    if (values.password !== values.confirmPassword) {
      message.error("两次密码不一致");
      return;
    }
    setLoading(true);
    try {
      const resp: RegisterResponse = await registerUser({
        account: values.account,
        username: values.username,
        password: values.password,
        email: values.email || undefined,
      });
      login(resp.token, {
        userId: resp.userId,
        account: values.account,
        username: values.username,
        isAdmin: 0,
      });
      message.success("注册成功");
      onClose();
    } catch {
      message.error("注册失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal open={open} onCancel={onClose} footer={null} title="注册" destroyOnClose>
      <Form layout="vertical" onFinish={handleSubmit}>
        <Form.Item name="account" rules={[{ required: true, message: "请输入账号" }]}>
          <Input placeholder="账号" />
        </Form.Item>
        <Form.Item name="username" rules={[{ required: true, message: "请输入用户名" }]}>
          <Input placeholder="用户名" />
        </Form.Item>
        <Form.Item name="password" rules={[{ required: true, message: "请输入密码" }]}>
          <Input.Password placeholder="密码" />
        </Form.Item>
        <Form.Item name="confirmPassword" rules={[{ required: true, message: "请确认密码" }]}>
          <Input.Password placeholder="确认密码" />
        </Form.Item>
        <Form.Item name="email">
          <Input placeholder="邮箱（可选）" />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} block>
            注册
          </Button>
        </Form.Item>
      </Form>
      <div style={{ textAlign: "center" }}>
        已有账号？
        <Button type="link" onClick={onSwitchToLogin}>
          去登录
        </Button>
      </div>
    </Modal>
  );
}
