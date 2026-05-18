import { useContext } from "react";
import { ChatSessionsContext } from "../contexts/ChatSessionsContextBase.ts";

export function useChatSessions() {
  const context = useContext(ChatSessionsContext);
  if (context === undefined) {
    throw new Error(
      "useChatSessionsContext must be used within a ChatSessionsProvider"
    );
  }
  return context;
}
