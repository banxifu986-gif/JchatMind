package com.kama.jchatmind.memory;

import com.kama.jchatmind.memory.LongMemEvalBlindReviewDecisionLoader.Decision;
import com.kama.jchatmind.memory.LongMemEvalBlindReviewDecisionLoader.ResolvedDecision;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class LongMemEvalM1ConfirmationPlanBuilder {

    private LongMemEvalM1ConfirmationPlanBuilder() {
    }

    static List<ResolvedDecision> buildPendingConfirmations(
            String caseId,
            String userId,
            Set<String> verifiedCaseIds,
            Set<String> allowedSessionIds,
            List<UserMemoryCandidate> candidates
    ) {
        if (!StringUtils.hasText(caseId) || !isPositiveNumericUserId(userId)
                || verifiedCaseIds == null || !verifiedCaseIds.contains(caseId)
                || allowedSessionIds == null || allowedSessionIds.isEmpty()
                || allowedSessionIds.stream().anyMatch(sessionId -> !StringUtils.hasText(sessionId))
                || candidates == null) {
            throw new IllegalArgumentException("LongMemEval M1 confirmation input 不完整");
        }

        List<ResolvedDecision> decisions = new ArrayList<>();
        Set<String> candidateIds = new HashSet<>();
        for (UserMemoryCandidate candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getId())
                    || !isUuid(candidate.getId())
                    || !candidateIds.add(candidate.getId())
                    || !userId.equals(candidate.getUserId())
                    || !isUuid(candidate.getSessionId())
                    || !allowedSessionIds.contains(candidate.getSessionId())) {
                throw new IllegalArgumentException("LongMemEval M1 candidate 不属于当前 case/user/session");
            }
            if (UserMemoryCandidate.STATUS_PENDING.equals(candidate.getStatus())) {
                if (!StringUtils.hasText(candidate.getEvidenceMessageId())
                        || !isUuid(candidate.getEvidenceMessageId())) {
                    throw new IllegalArgumentException("LongMemEval M1 pending candidate 缺少 evidence message");
                }
                String reviewItemId = UUID.nameUUIDFromBytes(
                        ("longmemeval-30-v1\u0000m1\u0000" + caseId + "\u0000" + candidate.getId())
                                .getBytes(StandardCharsets.UTF_8)
                ).toString();
                decisions.add(new ResolvedDecision(
                        reviewItemId,
                        caseId,
                        candidate.getId(),
                        candidate.getEvidenceMessageId(),
                        Decision.CONFIRM
                ));
            } else if (!UserMemoryCandidate.STATUS_PERSISTED.equals(candidate.getStatus())
                    && !UserMemoryCandidate.STATUS_DISCARDED.equals(candidate.getStatus())) {
                throw new IllegalArgumentException("LongMemEval M1 candidate 状态无效");
            }
        }
        decisions.sort(Comparator.comparing(ResolvedDecision::candidateId));
        return List.copyOf(decisions);
    }

    private static boolean isPositiveNumericUserId(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
