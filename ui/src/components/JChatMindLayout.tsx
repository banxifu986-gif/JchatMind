import { Routes, Route, useParams } from "react-router-dom";
import { useState } from "react";
import Layout from "../layout/Layout.tsx";
import Sidebar from "../layout/Sidebar.tsx";
import SideMenu from "./SideMenu.tsx";
import Content from "../layout/Content.tsx";
import AgentChatView from "./views/AgentChatView.tsx";
import type { AgentExecutionTraceItem } from "./views/agentChatView/AgentChatHistory.tsx";
import KnowledgeBaseView from "./views/KnowledgeBaseView.tsx";
import UserMemoryView from "./views/UserMemoryView.tsx";

interface SessionScopedAgentChatViewProps {
  sessionTraceCache: Map<string, AgentExecutionTraceItem[]>;
}

function SessionScopedAgentChatView({
  sessionTraceCache,
}: SessionScopedAgentChatViewProps) {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();

  return <AgentChatView key={chatSessionId ?? "new"} sessionTraceCache={sessionTraceCache} />;
}

export default function JChatMindLayout() {
  const [sessionTraceCache] = useState(
    () => new Map<string, AgentExecutionTraceItem[]>(),
  );

  return (
    <Layout>
      <Sidebar>
        <SideMenu />
      </Sidebar>
      <Content>
        <Routes>
          <Route path="/" element={<AgentChatView />} />
          <Route path="/agent" element={<AgentChatView />} />
          <Route
            path="/chat"
            element={<SessionScopedAgentChatView sessionTraceCache={sessionTraceCache} />}
          />
          <Route
            path="/chat/:chatSessionId"
            element={<SessionScopedAgentChatView sessionTraceCache={sessionTraceCache} />}
          />
          <Route path="/user-memory" element={<UserMemoryView />} />
          <Route path="/knowledge-base" element={<KnowledgeBaseView />} />
          <Route
            path="/knowledge-base/:knowledgeBaseId"
            element={<KnowledgeBaseView />}
          />
        </Routes>
      </Content>
    </Layout>
  );
}
