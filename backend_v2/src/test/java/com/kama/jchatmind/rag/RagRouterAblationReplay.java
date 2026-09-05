package com.kama.jchatmind.rag;

import java.util.List;

record RagRouterAblationReplay(
        String caseId,
        List<String> fixedRankedChunkIds,
        List<String> routerRankedChunkIds,
        String fixedRoute,
        String routerRoute,
        boolean fixedAbstained,
        boolean routerAbstained,
        boolean fixedPermissionViolation,
        boolean routerPermissionViolation,
        boolean routerExternalCall,
        long fixedLatencyMs,
        long routerLatencyMs,
        int fixedInputTokens,
        int routerInputTokens,
        boolean fixedCitationCorrect,
        boolean routerCitationCorrect
) {
}
