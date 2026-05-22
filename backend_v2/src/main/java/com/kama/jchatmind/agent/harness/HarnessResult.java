package com.kama.jchatmind.agent.harness;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
public class HarnessResult {

    private final Map<String, HarnessDecision> decisions = new LinkedHashMap<>();
    private final Map<String, List<HarnessContext>> groupedContexts = new LinkedHashMap<>();

    public void addContext(HarnessContext context) {
        groupedContexts.computeIfAbsent(context.getToolName(), key -> new ArrayList<>())
                .add(context);
        decisions.putIfAbsent(context.getToolCallId(), HarnessDecision.allow());
    }

    public void setDecision(String toolCallId, HarnessDecision decision) {
        decisions.put(toolCallId, decision);
    }

    public HarnessDecision getDecision(String toolCallId) {
        return decisions.get(toolCallId);
    }

    public List<HarnessContext> getContextsByToolName(String toolName) {
        return groupedContexts.getOrDefault(toolName, List.of());
    }

    public HarnessContext getContext(String toolCallId) {
        return groupedContexts.values().stream()
                .flatMap(List::stream)
                .filter(context -> context.getToolCallId().equals(toolCallId))
                .findFirst()
                .orElse(null);
    }

    public boolean hasPendingApprovals() {
        return decisions.values().stream()
                .anyMatch(decision -> decision.getStatus() == HarnessDecision.Status.PENDING_APPROVAL);
    }

    public List<HarnessContext> getPendingContexts() {
        return groupedContexts.values().stream()
                .flatMap(List::stream)
                .filter(context -> {
                    HarnessDecision decision = decisions.get(context.getToolCallId());
                    return decision != null && decision.getStatus() == HarnessDecision.Status.PENDING_APPROVAL;
                })
                .collect(Collectors.toList());
    }
}
