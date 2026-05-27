import React, { useEffect, useState, useCallback } from "react";
import {
  getChatSessions,
  deleteChatSession,
} from "../api/api.ts";
import { ChatSessionsContext } from "./ChatSessionsContextBase.ts";
import { useUser } from "../hooks/useUser.ts";
import type { ChatSessionVO } from "../api/api.ts";

export function ChatSessionsProvider({ children }: { children: React.ReactNode }) {
  const [chatSessions, setChatSessions] = useState<ChatSessionVO[]>([]);
  const [loading, setLoading] = useState(false);
  const { isLogin } = useUser();

  const fetchChatSessions = useCallback(async () => {
    if (!isLogin) return;
    setLoading(true);
    try {
      const resp = await getChatSessions();
      setChatSessions(resp.chatSessions);
    } finally {
      setLoading(false);
    }
  }, [isLogin]);

  useEffect(() => {
    fetchChatSessions();
  }, [fetchChatSessions]);

  const deleteChatSessionHandle = useCallback(async (chatSessionId: string) => {
    await deleteChatSession(chatSessionId);
    await fetchChatSessions();
  }, [fetchChatSessions]);

  return (
    <ChatSessionsContext.Provider
      value={{
        chatSessions,
        loading,
        refreshChatSessions: fetchChatSessions,
        deleteChatSession: deleteChatSessionHandle,
      }}
    >
      {children}
    </ChatSessionsContext.Provider>
  );
}

