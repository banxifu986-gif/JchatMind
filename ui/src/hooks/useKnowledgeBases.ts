import { useCallback, useEffect, useState } from "react";
import {
  createKnowledgeBase,
  type CreateKnowledgeBaseRequest,
  deleteKnowledgeBase,
  getKnowledgeBaseDeletionTask,
  getKnowledgeBases,
} from "../api/api.ts";
import type { KnowledgeBase } from "../types";

export function useKnowledgeBases() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);

  const refreshKnowledgeBases = useCallback(async () => {
    const resp = await getKnowledgeBases();
    // 将 KnowledgeBaseVO 转换为 KnowledgeBase 类型
    const converted = resp.knowledgeBases.map((kb) => ({
      knowledgeBaseId: kb.id,
      name: kb.name,
      description: kb.description || "",
    }));
    setKnowledgeBases(converted);
  }, []);

  useEffect(() => {
    void getKnowledgeBases().then((resp) => {
      // 将 KnowledgeBaseVO 转换为 KnowledgeBase 类型
      const converted = resp.knowledgeBases.map((kb) => ({
        knowledgeBaseId: kb.id,
        name: kb.name,
        description: kb.description || "",
      }));
      setKnowledgeBases(converted);
    });
  }, []);

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

