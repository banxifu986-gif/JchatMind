package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureCitationCandidateAnswerPipeline {

    static void requireSafeRetrieval(int routeViolations, int permissionViolations, int externalCalls) {
        if (routeViolations != 0 || permissionViolations != 0 || externalCalls != 0) {
            throw new IllegalStateException("candidate retrieval safety gate blocked answer generation");
        }
    }

    Report evaluate(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            Map<String, List<RagRetrievalResult>> fixedResultsByCaseId,
            Map<String, List<RagRetrievalResult>> routerResultsByCaseId,
            ChatClient generatorClient,
            String generatorModel
    ) {
        requireReviewedCandidate(candidate);
        if (plan == null) {
            throw new IllegalArgumentException("citation candidate runtime plan is required");
        }
        if (generatorClient != null && !StringUtils.hasText(generatorModel)) {
            throw new IllegalArgumentException("answer generator model name is required");
        }

        AnswerInputs fixedInputs = buildInputs(candidate, plan, fixedResultsByCaseId);
        AnswerInputs routerInputs = buildInputs(candidate, plan, routerResultsByCaseId);
        G2CurrentFixtureAnswerGenerationEvaluator generationEvaluator =
                new G2CurrentFixtureAnswerGenerationEvaluator(generatorClient, generatorModel);
        G2CurrentFixtureAnswerGenerationEvaluator.Report fixedGeneration =
                generationEvaluator.generate(candidate, fixedInputs.contextsByCaseId());
        G2CurrentFixtureAnswerGenerationEvaluator.Report routerGeneration =
                generationEvaluator.generate(candidate, routerInputs.contextsByCaseId());
        G2CurrentFixtureCitationCandidateEvaluator citationEvaluator =
                new G2CurrentFixtureCitationCandidateEvaluator();
        G2CurrentFixtureCitationCandidateEvaluator.Report fixedCitation = citationEvaluator.evaluate(
                candidate,
                plan.logicalChunkIdByRuntimeUuid(),
                fixedInputs.retrievedRuntimeChunkIdsByCaseId(),
                fixedGeneration.finalAnswersByCaseId()
        );
        G2CurrentFixtureCitationCandidateEvaluator.Report routerCitation = citationEvaluator.evaluate(
                candidate,
                plan.logicalChunkIdByRuntimeUuid(),
                routerInputs.retrievedRuntimeChunkIdsByCaseId(),
                routerGeneration.finalAnswersByCaseId()
        );
        boolean eligible = "eligible_for_candidate_citation_contract".equals(fixedCitation.gate().verdict())
                && "eligible_for_candidate_citation_contract".equals(routerCitation.gate().verdict());
        return new Report(
                candidate.datasetId(),
                generatorClient != null,
                generatorModel,
                fixedGeneration,
                routerGeneration,
                fixedCitation,
                routerCitation,
                eligible ? "eligible_for_candidate_citation_contract" : "inconclusive",
                true,
                false
        );
    }

    static void requireReviewedCandidate(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate
    ) {
        if (candidate == null || !"candidate".equals(candidate.status())
                || !"approved".equals(candidate.reviewStatus()) || candidate.cases() == null
                || candidate.cases().isEmpty()
                || candidate.cases().stream().anyMatch(item -> item == null
                || !"approved".equals(item.reviewStatus()))) {
            throw new IllegalStateException("citation candidate 必须全部通过 review 后才能评测");
        }
    }

    private AnswerInputs buildInputs(
            G2CurrentFixtureCitationCandidateLoader.CandidateDataset candidate,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            Map<String, List<RagRetrievalResult>> resultsByCaseId
    ) {
        Set<String> caseIds = candidate.cases().stream()
                .map(G2CurrentFixtureCitationCandidateLoader.CandidateCase::caseId)
                .collect(java.util.stream.Collectors.toSet());
        if (resultsByCaseId == null || !resultsByCaseId.keySet().equals(caseIds)) {
            throw new IllegalStateException("candidate retrieval results 必须精确覆盖 approved cases");
        }
        Map<String, String> contexts = new LinkedHashMap<>();
        Map<String, Set<String>> retrievedIdsByCaseId = new LinkedHashMap<>();
        for (G2CurrentFixtureCitationCandidateLoader.CandidateCase item : candidate.cases()) {
            List<RagRetrievalResult> results = resultsByCaseId.get(item.caseId());
            if (results == null) {
                throw new IllegalStateException("candidate retrieval result list must not be null: " + item.caseId());
            }
            StringBuilder context = new StringBuilder();
            Set<String> retrievedIds = new HashSet<>();
            for (RagRetrievalResult result : results) {
                if (result == null || !StringUtils.hasText(result.getChunkId())
                        || !retrievedIds.add(result.getChunkId())) {
                    throw new IllegalArgumentException("candidate retrieval chunk ID 缺失或重复: " + item.caseId());
                }
                if (!plan.knowledgeBaseId().equals(result.getKbId())) {
                    throw new IllegalArgumentException("candidate retrieval 越过授权 KB: " + item.caseId());
                }
                if (!plan.logicalChunkIdByRuntimeUuid().containsKey(result.getChunkId())) {
                    throw new IllegalArgumentException("candidate retrieval 含未知 runtime chunk UUID: "
                            + result.getChunkId());
                }
                if (!StringUtils.hasText(result.getContent())) {
                    continue;
                }
                if (context.length() > 0) {
                    context.append("\n\n");
                }
                context.append("引用: ").append(result.getChunkId()).append('\n').append(result.getContent());
            }
            contexts.put(item.caseId(), context.toString());
            retrievedIdsByCaseId.put(item.caseId(), Set.copyOf(retrievedIds));
        }
        return new AnswerInputs(contexts, retrievedIdsByCaseId);
    }

    private record AnswerInputs(
            Map<String, String> contextsByCaseId,
            Map<String, Set<String>> retrievedRuntimeChunkIdsByCaseId
    ) {
        private AnswerInputs {
            contextsByCaseId = Map.copyOf(contextsByCaseId);
            retrievedRuntimeChunkIdsByCaseId = Map.copyOf(retrievedRuntimeChunkIdsByCaseId);
        }
    }

    record Report(
            String datasetId,
            boolean answerGenerationEnabled,
            String generatorModel,
            G2CurrentFixtureAnswerGenerationEvaluator.Report fixedGeneration,
            G2CurrentFixtureAnswerGenerationEvaluator.Report routerGeneration,
            G2CurrentFixtureCitationCandidateEvaluator.Report fixedCitation,
            G2CurrentFixtureCitationCandidateEvaluator.Report routerCitation,
            String citationGateStatus,
            boolean candidateOnly,
            boolean releaseEligible
    ) {
    }
}
