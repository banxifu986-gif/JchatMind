import { useCallback, useEffect, useState } from "react";
import {
  createKnowledgeBase,
  type CreateKnowledgeBaseRequest,
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

  return {
    knowledgeBases,
    createKnowledgeBaseHandle,
    refreshKnowledgeBases,
  };
}

