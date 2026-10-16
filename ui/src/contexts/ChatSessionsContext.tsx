import React, { useEffect, useState, useCallback } from "react";
import {
  getChatSessions,
  deleteChatSession,
} from "../api/api.ts";
import { ChatSessionsContext } from "./ChatSessionsContextBase.ts";
import { useUser } from "../hooks/useUser.ts";
import type { ChatSessionVO } from "../api/api.ts";

export function ChatSessionsProvider({ children }: { children: React.ReactNode }) {
  const [sessionState, setSessionState] = useState<{
    userId: number | null;
    sessions: ChatSessionVO[];
  }>({ userId: null, sessions: [] });
  const [loading, setLoading] = useState(false);
  const { isLogin, user } = useUser();
  const userId = user?.userId ?? null;
  const chatSessions = isLogin && sessionState.userId === userId
    ? sessionState.sessions : [];

  const fetchChatSessions = useCallback(async () => {
    if (!isLogin || userId === null) return;
    setLoading(true);
    try {
      const resp = await getChatSessions();
      setSessionState({ userId, sessions: resp.chatSessions });
    } finally {
      setLoading(false);
    }
  }, [isLogin, userId]);

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
        loading: isLogin && loading,
        refreshChatSessions: fetchChatSessions,
        deleteChatSession: deleteChatSessionHandle,
      }}
    >
      {children}
    </ChatSessionsContext.Provider>
  );
}

