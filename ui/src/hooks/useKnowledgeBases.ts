import { useCallback, useEffect, useState } from "react";
import {
  createKnowledgeBase,
  type CreateKnowledgeBaseRequest,
  deleteKnowledgeBase,
  getKnowledgeBaseDeletionTask,
  getKnowledgeBases,
} from "../api/api.ts";
import type { KnowledgeBase } from "../types";
import { useUser } from "./useUser.ts";

export function useKnowledgeBases() {
  const { isLogin, user } = useUser();
  const [knowledgeBaseState, setKnowledgeBaseState] = useState<{
    userId: number | null;
    knowledgeBases: KnowledgeBase[];
  }>({ userId: null, knowledgeBases: [] });
  const knowledgeBases = isLogin && knowledgeBaseState.userId === user?.userId
    ? knowledgeBaseState.knowledgeBases : [];

  const refreshKnowledgeBases = useCallback(async () => {
    const resp = await getKnowledgeBases();
    // 将 KnowledgeBaseVO 转换为 KnowledgeBase 类型
    const converted = resp.knowledgeBases.map((kb) => ({
      knowledgeBaseId: kb.id,
      name: kb.name,
      description: kb.description || "",
    }));
    setKnowledgeBaseState({ userId: user?.userId ?? null, knowledgeBases: converted });
  }, [user?.userId]);

  useEffect(() => {
    if (!isLogin) {
      return;
    }
    let active = true;
    void getKnowledgeBases().then((resp) => {
      if (!active) return;
      // 将 KnowledgeBaseVO 转换为 KnowledgeBase 类型
      const converted = resp.knowledgeBases.map((kb) => ({
        knowledgeBaseId: kb.id,
        name: kb.name,
        description: kb.description || "",
      }));
      setKnowledgeBaseState({ userId: user?.userId ?? null, knowledgeBases: converted });
    });
    return () => { active = false; };
  }, [isLogin, user?.userId]);

  async function createKnowledgeBaseHandle(
    request: CreateKnowledgeBaseRequest,
  ) {
    await createKnowledgeBase(request);
    await refreshKnowledgeBases();
  }

  async function deleteKnowledgeBaseHandle(knowledgeBaseId: string) {
    const { deletionTaskId } = await deleteKnowledgeBase(knowledgeBaseId);
    for (let attempt = 0; attempt < 60; attempt += 1) {
      const task = await getKnowledgeBaseDeletionTask(deletionTaskId);
      if (task.status === "SUCCEEDED") {
        await refreshKnowledgeBases();
        return;
      }
      if (task.status === "DEAD_LETTER") {
        throw new Error(task.errorSummary || "知识库删除失败");
      }
      await new Promise((resolve) => window.setTimeout(resolve, 500));
    }
    throw new Error("知识库删除任务查询超时");
  }

  return {
    knowledgeBases,
    createKnowledgeBaseHandle,
    deleteKnowledgeBaseHandle,
    refreshKnowledgeBases,
  };
}

