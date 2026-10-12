package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureAnswerContextBuilder {

    AnswerInputs build(
            G2CurrentFixtureProfile profile,
            Map<String, List<RagRetrievalResult>> retrievedByCaseId
    ) {
        Set<String> caseIds = validateProfile(profile);
        if (retrievedByCaseId == null || !retrievedByCaseId.keySet().equals(caseIds)) {
            throw new IllegalStateException("retrieval results must exactly cover current fixture cases");
        }

        Map<String, String> contextsByCaseId = new LinkedHashMap<>();
        Map<String, Set<String>> retrievedIdsByCaseId = new LinkedHashMap<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            List<RagRetrievalResult> results = retrievedByCaseId.get(item.caseId());
            if (results == null) {
                throw new IllegalStateException("retrieval result list must not be null: " + item.caseId());
            }

            Set<String> retrievedIds = new HashSet<>();
            StringBuilder context = new StringBuilder();
            for (RagRetrievalResult result : results) {
                if (result == null || !StringUtils.hasText(result.getChunkId())) {
                    throw new IllegalArgumentException("retrieval result chunk ID is missing: " + item.caseId());
                }
                if (!retrievedIds.add(result.getChunkId())) {
                    throw new IllegalArgumentException("duplicate runtime chunk ID: " + result.getChunkId());
                }
                if (!StringUtils.hasText(result.getContent())) {
                    continue;
                }
                if (context.length() > 0) {
                    context.append("\n\n");
                }
                context.append("引用: ").append(result.getChunkId())
                        .append('\n').append(result.getContent());
            }

            contextsByCaseId.put(item.caseId(), context.toString());
            retrievedIdsByCaseId.put(item.caseId(), Set.copyOf(retrievedIds));
        }
        return new AnswerInputs(contextsByCaseId, retrievedIdsByCaseId);
    }

    private Set<String> validateProfile(G2CurrentFixtureProfile profile) {
        if (profile == null || !"fixture-fast-v1".equals(profile.datasetId())
                || profile.cases() == null || profile.caseCount() != profile.cases().size()) {
            throw new IllegalArgumentException("current fixture answer context profile 无效");
        }
        Set<String> caseIds = new HashSet<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            if (item == null || !StringUtils.hasText(item.caseId()) || !caseIds.add(item.caseId())) {
                throw new IllegalArgumentException("current fixture answer context case 无效或重复");
            }
        }
        return caseIds;
    }

    record AnswerInputs(
            Map<String, String> contextsByCaseId,
            Map<String, Set<String>> retrievedRuntimeChunkIdsByCaseId
    ) {
        AnswerInputs {
            contextsByCaseId = Map.copyOf(contextsByCaseId);
            retrievedRuntimeChunkIdsByCaseId = Map.copyOf(retrievedRuntimeChunkIdsByCaseId);
        }
    }
}
