package com.kama.jchatmind.rag;

import java.util.List;

final class RagIndependentBranchRuntimeScopeMapper {

    private static final List<String> FROZEN_LOGICAL_SCOPE = List.of("g2-baseline-kb");

    private RagIndependentBranchRuntimeScopeMapper() {
    }

    static List<String> toRuntimeKbScope(List<String> logicalScope, String runtimeKnowledgeBaseId) {
        if (!FROZEN_LOGICAL_SCOPE.equals(logicalScope)) {
            throw new IllegalStateException("独立三路运行时只接受冻结逻辑 KB scope");
        }
        if (runtimeKnowledgeBaseId == null || runtimeKnowledgeBaseId.isBlank()) {
            throw new IllegalArgumentException("独立三路运行时缺少导入 KB UUID");
        }
        return List.of(runtimeKnowledgeBaseId);
    }
}
