import { useEffect, useState } from "react";
import {
  type AgentVO,
  createAgent,
  type CreateAgentRequest,
  getAgents,
  deleteAgent,
  updateAgent,
  type UpdateAgentRequest,
} from "../api/api.ts";
import { useUser } from "./useUser.ts";

export function useAgents() {
  const { isLogin, user } = useUser();
  const [agentState, setAgentState] = useState<{
    userId: number | null;
    agents: AgentVO[];
  }>({ userId: null, agents: [] });

  useEffect(() => {
    if (!isLogin || !user) {
      return;
    }
    const userId = user.userId;

    async function fetchData() {
      try {
        const resp = await getAgents();
        setAgentState({ userId, agents: resp.agents });
      } catch {
        setAgentState({ userId, agents: [] });
      }
    }

    fetchData().then();
  }, [isLogin, user]);

  async function refreshAgents() {
    const resp = await getAgents();
    setAgentState({ userId: user?.userId ?? null, agents: resp.agents });
  }

  async function createAgentHandle(agent: CreateAgentRequest) {
    await createAgent(agent);
    await refreshAgents();
  }

  async function deleteAgentHandle(agentId: string) {
    await deleteAgent(agentId);
    await refreshAgents();
  }

  async function updateAgentHandle(
    agentId: string,
    request: UpdateAgentRequest,
  ) {
    await updateAgent(agentId, request);
    await refreshAgents();
  }

  return {
    agents: agentState.userId === user?.userId ? agentState.agents : [],
    createAgentHandle,
    deleteAgentHandle,
    updateAgentHandle,
    refreshAgents,
  };
}
