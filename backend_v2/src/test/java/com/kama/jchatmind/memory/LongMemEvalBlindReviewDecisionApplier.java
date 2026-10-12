package com.kama.jchatmind.memory;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LongMemEvalBlindReviewDecisionApplier {

    private LongMemEvalBlindReviewDecisionApplier() {
    }

    static void apply(
            List<LongMemEvalBlindReviewDecisionLoader.ResolvedDecision> decisions,
            Map<String, String> userIdByCaseId,
            RequestScopeData requestScopeData,
            UserMemoryFacadeService userMemoryFacadeService
    ) {
        if (decisions == null || decisions.isEmpty() || userIdByCaseId == null
                || requestScopeData == null || userMemoryFacadeService == null) {
            throw new IllegalArgumentException("LongMemEval blind review decision apply input 不完整");
        }

        Map<String, Long> numericUserIds = validateUserScopes(decisions, userIdByCaseId);
        Set<String> candidateIds = new HashSet<>();
        for (LongMemEvalBlindReviewDecisionLoader.ResolvedDecision decision : decisions) {
            if (decision == null || !StringUtils.hasText(decision.caseId())
                    || !StringUtils.hasText(decision.candidateId())
                    || decision.decision() == null
                    || !candidateIds.add(decision.candidateId())) {
                throw new IllegalArgumentException("LongMemEval blind review decision 无效或 candidateId 重复");
            }
        }

        Long previousUserId = requestScopeData.getUserId();
        boolean previousLogin = requestScopeData.isLogin();
        try {
            for (LongMemEvalBlindReviewDecisionLoader.ResolvedDecision decision : decisions) {
                requestScopeData.setUserId(numericUserIds.get(decision.caseId()));
                requestScopeData.setLogin(true);
                if (decision.decision() == LongMemEvalBlindReviewDecisionLoader.Decision.CONFIRM) {
                    userMemoryFacadeService.confirmUserMemoryCandidate(decision.candidateId());
                } else {
                    userMemoryFacadeService.discardUserMemoryCandidate(decision.candidateId());
                }
            }
        } finally {
            requestScopeData.setUserId(previousUserId);
            requestScopeData.setLogin(previousLogin);
        }
    }

    private static Map<String, Long> validateUserScopes(
            List<LongMemEvalBlindReviewDecisionLoader.ResolvedDecision> decisions,
            Map<String, String> userIdByCaseId
    ) {
        Set<String> caseIds = new HashSet<>();
        for (LongMemEvalBlindReviewDecisionLoader.ResolvedDecision decision : decisions) {
            if (decision == null || !StringUtils.hasText(decision.caseId())
                    || !userIdByCaseId.containsKey(decision.caseId())) {
                throw new IllegalArgumentException("LongMemEval decision 缺少对应 virtual user scope");
            }
            caseIds.add(decision.caseId());
        }

        Map<String, Long> numericUserIds = new HashMap<>();
        Set<Long> distinctUserIds = new HashSet<>();
        for (String caseId : caseIds) {
            String userIdText = userIdByCaseId.get(caseId);
            long userId;
            try {
                userId = Long.parseLong(userIdText);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("LongMemEval virtual user ID 必须为数字", exception);
            }
            if (userId <= 0 || !distinctUserIds.add(userId)) {
                throw new IllegalArgumentException("LongMemEval case 必须使用独立且为正数的 virtual user ID");
            }
            numericUserIds.put(caseId, userId);
        }
        return Map.copyOf(numericUserIds);
    }
}
