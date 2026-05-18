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
  const { userId } = useUser();

  const fetchChatSessions = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await getChatSessions(userId);
      setChatSessions(resp.chatSessions);
    } finally {
      setLoading(false);
    }
  }, [userId]);

  useEffect(() => {
    fetchChatSessions();
  }, [fetchChatSessions]);

  const deleteChatSessionHandle = useCallback(async (chatSessionId: string) => {
    await deleteChatSession(userId, chatSessionId);
    await fetchChatSessions();
  }, [fetchChatSessions, userId]);

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

