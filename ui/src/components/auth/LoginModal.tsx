import { useState } from "react";
import { Modal, Form, Input, Button, Tabs, message } from "antd";
import { loginUser } from "../../api/api.ts";
import type { LoginResponse } from "../../api/api.ts";
import { useUser } from "../../hooks/useUser.ts";

interface Props {
  open: boolean;
  onClose: () => void;
  onSwitchToRegister: () => void;
}

export function LoginModal({ open, onClose, onSwitchToRegister }: Props) {
  const { login } = useUser();
  const [loading, setLoading] = useState(false);
  const [mode, setMode] = useState<"password" | "verifyCode">("password");

  const handleSubmit = async (values: Record<string, string>) => {
    setLoading(true);
    try {
      const resp: LoginResponse = await loginUser({
        account: mode === "password" ? values.account : undefined,
        email: values.email,
        password: mode === "password" ? values.password : undefined,
        verifyCode: mode === "verifyCode" ? values.verifyCode : undefined,
      });
      login(resp.token, {
        userId: resp.userId,
        account: resp.account,
        username: resp.username,
        avatarUrl: resp.avatarUrl,
        isAdmin: resp.isAdmin,
        email: resp.email,
      });
      message.success("登录成功");
      onClose();
    } catch {
      message.error("登录失败，请检查凭证");
    } finally {
      setLoading(false);
    }
  };

  const tabItems = [
    {
      key: "password",
      label: "密码登录",
      children: (
        <Form layout="vertical" onFinish={handleSubmit}>
          <Form.Item name="account" rules={[{ required: true, message: "请输入账号" }]}>
            <Input placeholder="账号" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: "请输入密码" }]}>
            <Input.Password placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登录
            </Button>
          </Form.Item>
        </Form>
      ),
    },
    {
      key: "verifyCode",
      label: "验证码登录",
      children: (
        <Form layout="vertical" onFinish={handleSubmit}>
          <Form.Item name="email" rules={[{ required: true, message: "请输入邮箱" }]}>
            <Input placeholder="邮箱" />
          </Form.Item>
          <Form.Item name="verifyCode" rules={[{ required: true, message: "请输入验证码" }]}>
            <Input placeholder="验证码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登录
            </Button>
          </Form.Item>
        </Form>
      ),
    },
  ];

  return (
    <Modal open={open} onCancel={onClose} footer={null} title="登录" destroyOnClose>
      <Tabs activeKey={mode} onChange={(key) => setMode(key as "password" | "verifyCode")} items={tabItems} />
      <div style={{ textAlign: "center" }}>
        还没有账号？
        <Button type="link" onClick={onSwitchToRegister}>
          去注册
        </Button>
      </div>
    </Modal>
  );
}
