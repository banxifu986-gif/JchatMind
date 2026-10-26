import React, { useState } from "react";
import { Sender } from "@ant-design/x";

interface AgentChatInputProps {
  onSend: (message: string) => void;
}

const AgentChatInput: React.FC<AgentChatInputProps> = ({ onSend }) => {
  const [message, setMessage] = useState("");

  return (
    <div className="app-chat__composer-shell app-chat__composer-shell--active">
      <Sender
        onSubmit={() => {
          const trimmedMessage = message.trim();
          if (!trimmedMessage) {
            return;
          }
          onSend(trimmedMessage);
          setMessage("");
        }}
        placeholder="输入消息..."
        value={message}
        onChange={setMessage}
      />
    </div>
  );
};

export default AgentChatInput;
