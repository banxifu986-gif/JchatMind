package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.intent.IntentDecision;
import com.kama.jchatmind.intent.IntentFallbackRouter;
import com.kama.jchatmind.intent.IntentPolicyEngine;
import com.kama.jchatmind.intent.IntentRoutingProperties;
import com.kama.jchatmind.intent.IntentRouter;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public class ToolSelectionPolicy {

    private final IntentFallbackRouter intentFallbackRouter;
    private final IntentPolicyEngine intentPolicyEngine;

    public ToolSelectionPolicy() {
        this(
                new IntentFallbackRouter(
                        new IntentRouter(),
                        new ObjectMapper(),
                        null,
                        new IntentRoutingProperties(),
                        false,
                        null
                ),
                new IntentPolicyEngine()
        );
    }

    ToolSelectionPolicy(
            IntentFallbackRouter intentFallbackRouter,
            IntentPolicyEngine intentPolicyEngine
    ) {
        this.intentFallbackRouter = intentFallbackRouter;
        this.intentPolicyEngine = intentPolicyEngine;
    }

    public IntentDecision decide(String query) {
        return intentFallbackRouter.decide(query);
    }

    public IntentDecision decide(String query, String previousUserQuery) {
        return intentFallbackRouter.decide(query, previousUserQuery);
    }

    public IntentDecision decide(
            String query,
            String previousUserQuery,
            String traceId,
            String correlationId
    ) {
        return intentFallbackRouter.decide(query, previousUserQuery, traceId, correlationId);
    }

    public List<ToolCallback> select(List<ToolCallback> availableTools, String query) {
        return select(availableTools, decide(query));
    }

    public List<ToolCallback> select(
            List<ToolCallback> availableTools,
            String query,
            String previousUserQuery
    ) {
        return select(availableTools, decide(query, previousUserQuery));
    }

    public List<ToolCallback> select(List<ToolCallback> availableTools, IntentDecision decision) {
        return intentPolicyEngine.select(availableTools, decision);
    }
}
