package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureRefusalEvaluator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_CONTEXT_CHARACTERS = 12_000;
    private static final String SYSTEM_PROMPT = """
            The case is labelled unanswerable from the supplied context. Judge whether the final answer
            clearly abstains and avoids asserting unsupported facts. Do not use outside knowledge.
            Return exactly one JSON object: {"correctAbstention": true or false}.
            """;

    private final ChatClient judgeClient;
    private final String judgeModel;

    G2CurrentFixtureRefusalEvaluator(ChatClient judgeClient, String judgeModel) {
        this.judgeClient = judgeClient;
        this.judgeModel = StringUtils.hasText(judgeModel) ? judgeModel.trim() : null;
    }

    Report evaluate(
            G2CurrentFixtureProfile profile,
            Map<String, String> contextsByCaseId,
            Map<String, String> answersByCaseId
    ) {
        if (profile == null || !"fixture-fast-v1".equals(profile.datasetId())
                || profile.cases() == null || profile.caseCount() != profile.cases().size()) {
            throw new IllegalArgumentException("current fixture refusal profile 无效");
        }
        Set<String> caseIds = new HashSet<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            if (item == null || !StringUtils.hasText(item.caseId()) || !StringUtils.hasText(item.query())
                    || !caseIds.add(item.caseId())) {
                throw new IllegalArgumentException("current fixture refusal case 无效或重复");
            }
        }
        if (contextsByCaseId == null || answersByCaseId == null
                || !contextsByCaseId.keySet().equals(caseIds)
                || !answersByCaseId.keySet().equals(caseIds)) {
            throw new IllegalStateException("refusal contexts/answers 必须精确覆盖 current fixture");
        }

        List<CaseScore> scores = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        int noAnswerCount = 0;
        int evaluatedCount = 0;
        int correctCount = 0;
        int judgeCallCount = 0;
        for (G2CurrentFixtureCase item : profile.cases()) {
            if (!item.shouldAbstain()) {
                continue;
            }
            noAnswerCount++;
            String context = contextsByCaseId.get(item.caseId());
            String answer = answersByCaseId.get(item.caseId());
            String skipReason = judgeClient == null ? "judge_disabled"
                    : !StringUtils.hasText(context) ? "empty_context"
                    : !StringUtils.hasText(answer) ? "empty_answer" : null;
            if (skipReason != null) {
                addSkipped(scores, skipReasons, item.caseId(), skipReason, false, 0L);
                continue;
            }

            boolean contextTruncated = context.length() > MAX_CONTEXT_CHARACTERS;
            String boundedContext = contextTruncated ? context.substring(0, MAX_CONTEXT_CHARACTERS) : context;
            long startedAt = System.nanoTime();
            judgeCallCount++;
            try {
                String response = judgeClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(buildPrompt(item.query(), boundedContext, answer))
                        .call()
                        .content();
                long latencyMs = elapsedMillis(startedAt);
                latencies.add(latencyMs);
                boolean correctAbstention;
                try {
                    correctAbstention = parseDecision(response);
                } catch (IllegalArgumentException | JsonProcessingException exception) {
                    addSkipped(scores, skipReasons, item.caseId(), "invalid_judge_response", contextTruncated,
                            latencyMs);
                    continue;
                }
                scores.add(new CaseScore(item.caseId(), true, null, correctAbstention, contextTruncated, latencyMs));
                evaluatedCount++;
                if (correctAbstention) {
                    correctCount++;
                }
            } catch (Exception exception) {
                long latencyMs = elapsedMillis(startedAt);
                latencies.add(latencyMs);
                addSkipped(scores, skipReasons, item.caseId(), "judge_error", contextTruncated, latencyMs);
            }
        }

        String status = judgeClient == null ? "disabled"
                : evaluatedCount == noAnswerCount ? "complete"
                : evaluatedCount == 0 ? "skipped" : "partial";
        return new Report(profile.datasetId(), judgeModel, status, noAnswerCount, evaluatedCount,
                noAnswerCount - evaluatedCount, correctCount,
                evaluatedCount == 0 ? 0D : correctCount / (double) evaluatedCount,
                judgeCallCount, percentile95(latencies), Map.copyOf(skipReasons), List.copyOf(scores),
                "not_configured");
    }

    private String buildPrompt(String query, String context, String answer) {
        return "问题:\n" + query + "\n\n检索上下文:\n" + context
                + "\n\n最终回答:\n" + answer
                + "\n\n该 case 应拒答；判断回答是否明确拒答且没有断言上下文不支持的事实。";
    }

    private boolean parseDecision(String response) throws JsonProcessingException {
        if (!StringUtils.hasText(response)) {
            throw new IllegalArgumentException("refusal judge response is blank");
        }
        String json = response.trim().replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "").trim();
        JsonNode root = OBJECT_MAPPER.readTree(json);
        if (!root.isObject() || root.size() != 1 || !root.path("correctAbstention").isBoolean()) {
            throw new IllegalArgumentException("refusal judge response schema is invalid");
        }
        return root.path("correctAbstention").asBoolean();
    }

    private void addSkipped(
            List<CaseScore> scores,
            Map<String, Integer> skipReasons,
            String caseId,
            String reason,
            boolean contextTruncated,
            long latencyMs
    ) {
        scores.add(new CaseScore(caseId, false, reason, null, contextTruncated, latencyMs));
        skipReasons.merge(reason, 1, Integer::sum);
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private long percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(Math.max(0, (int) Math.ceil(0.95D * sorted.size()) - 1));
    }

    record CaseScore(
            String caseId,
            boolean evaluated,
            String skipReason,
            Boolean correctAbstention,
            boolean contextTruncated,
            long judgeLatencyMs
    ) {
    }

    record Report(
            String datasetId,
            String judgeModel,
            String status,
            int noAnswerCaseCount,
            int evaluatedCaseCount,
            int skippedCaseCount,
            int correctAbstentionCount,
            double abstentionAccuracy,
            int judgeCallCount,
            long judgeP95LatencyMs,
            Map<String, Integer> skipReasons,
            List<CaseScore> cases,
            String qualityGateStatus
    ) {
        Report {
            skipReasons = Map.copyOf(skipReasons);
            cases = List.copyOf(cases);
        }
    }
}
