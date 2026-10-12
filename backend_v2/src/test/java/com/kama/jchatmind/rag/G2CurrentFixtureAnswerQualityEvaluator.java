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

final class G2CurrentFixtureAnswerQualityEvaluator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_CONTEXT_CHARACTERS = 12_000;
    private static final String SYSTEM_PROMPT = """
            Judge only from the supplied query and retrieved context. Do not use outside knowledge.
            Return exactly JSON with numeric faithfulness and answerRelevancy scores from 0 to 1.
            """;

    private final ChatClient judgeClient;
    private final String judgeModel;

    G2CurrentFixtureAnswerQualityEvaluator(ChatClient judgeClient, String judgeModel) {
        this.judgeClient = judgeClient;
        this.judgeModel = StringUtils.hasText(judgeModel) ? judgeModel.trim() : null;
    }

    G2CurrentFixtureAnswerQualityReport evaluate(
            G2CurrentFixtureProfile profile,
            Map<String, String> contextsByCaseId,
            Map<String, String> answersByCaseId
    ) {
        if (profile == null || !"fixture-fast-v1".equals(profile.datasetId())
                || profile.cases() == null || profile.caseCount() != profile.cases().size()) {
            throw new IllegalArgumentException("current fixture answer quality profile 无效");
        }
        Set<String> caseIds = new HashSet<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            if (item == null || !StringUtils.hasText(item.caseId()) || !StringUtils.hasText(item.query())
                    || !caseIds.add(item.caseId())) {
                throw new IllegalArgumentException("current fixture answer quality case 无效或重复");
            }
        }
        if (contextsByCaseId == null || answersByCaseId == null
                || !contextsByCaseId.keySet().equals(caseIds)
                || !answersByCaseId.keySet().equals(caseIds)) {
            throw new IllegalStateException("answer quality contexts/answers 必须精确覆盖 current fixture");
        }

        List<G2CurrentFixtureAnswerQualityReport.CaseScore> caseScores = new ArrayList<>(caseIds.size());
        List<Long> judgeLatencies = new ArrayList<>();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        int answerableCount = 0;
        int noAnswerCount = 0;
        int evaluatedCount = 0;
        int judgeCallCount = 0;
        double faithfulnessTotal = 0D;
        double relevancyTotal = 0D;

        for (G2CurrentFixtureCase item : profile.cases()) {
            String caseId = item.caseId();
            if (item.shouldAbstain()) {
                noAnswerCount++;
                addSkipped(caseScores, skipReasons, caseId, "no_answer_case", false, 0L);
                continue;
            }
            answerableCount++;
            String context = contextsByCaseId.get(caseId);
            String answer = answersByCaseId.get(caseId);
            String skipReason = judgeClient == null ? "judge_disabled"
                    : !StringUtils.hasText(context) ? "empty_context"
                    : !StringUtils.hasText(answer) ? "empty_answer" : null;
            if (skipReason != null) {
                addSkipped(caseScores, skipReasons, caseId, skipReason, false, 0L);
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
                judgeLatencies.add(latencyMs);
                Score score;
                try {
                    score = parseScore(response);
                } catch (IllegalArgumentException | JsonProcessingException exception) {
                    addSkipped(caseScores, skipReasons, caseId, "invalid_judge_response", contextTruncated,
                            latencyMs);
                    continue;
                }
                caseScores.add(new G2CurrentFixtureAnswerQualityReport.CaseScore(
                        caseId, true, null, score.faithfulness(), score.answerRelevancy(), contextTruncated, latencyMs
                ));
                evaluatedCount++;
                faithfulnessTotal += score.faithfulness();
                relevancyTotal += score.answerRelevancy();
            } catch (Exception exception) {
                long latencyMs = elapsedMillis(startedAt);
                judgeLatencies.add(latencyMs);
                addSkipped(caseScores, skipReasons, caseId, "judge_error", contextTruncated, latencyMs);
            }
        }

        String status = judgeClient == null ? "disabled"
                : evaluatedCount == answerableCount ? "complete"
                : evaluatedCount == 0 ? "skipped" : "partial";
        return new G2CurrentFixtureAnswerQualityReport(
                profile.datasetId(), judgeModel, status, caseIds.size(), answerableCount, noAnswerCount,
                evaluatedCount, caseIds.size() - evaluatedCount, judgeCallCount, percentile95(judgeLatencies),
                evaluatedCount == 0 ? 0D : faithfulnessTotal / evaluatedCount,
                evaluatedCount == 0 ? 0D : relevancyTotal / evaluatedCount,
                skipReasons, caseScores, evaluatedCount > 0, "not_configured");
    }

    private String buildPrompt(String query, String context, String answer) {
        return "Query:\n" + query + "\n\nRetrieved context:\n" + context
                + "\n\nFinal answer:\n" + answer
                + "\n\nScore faithfulness from context support and answerRelevancy from direct response to query.";
    }

    private Score parseScore(String response) throws JsonProcessingException {
        if (!StringUtils.hasText(response)) {
            throw new IllegalArgumentException("judge response is blank");
        }
        String json = response.trim().replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "").trim();
        JsonNode root = OBJECT_MAPPER.readTree(json);
        if (!root.isObject() || root.size() != 2 || !root.path("faithfulness").isNumber()
                || !root.path("answerRelevancy").isNumber()) {
            throw new IllegalArgumentException("judge response schema is invalid");
        }
        double faithfulness = root.path("faithfulness").asDouble();
        double relevancy = root.path("answerRelevancy").asDouble();
        if (!Double.isFinite(faithfulness) || faithfulness < 0D || faithfulness > 1D
                || !Double.isFinite(relevancy) || relevancy < 0D || relevancy > 1D) {
            throw new IllegalArgumentException("judge scores must be finite values in [0, 1]");
        }
        return new Score(faithfulness, relevancy);
    }

    private void addSkipped(
            List<G2CurrentFixtureAnswerQualityReport.CaseScore> scores,
            Map<String, Integer> skipReasons,
            String caseId,
            String reason,
            boolean contextTruncated,
            long latencyMs
    ) {
        scores.add(new G2CurrentFixtureAnswerQualityReport.CaseScore(
                caseId, false, reason, null, null, contextTruncated, latencyMs
        ));
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

    private record Score(double faithfulness, double answerRelevancy) {
    }

}
