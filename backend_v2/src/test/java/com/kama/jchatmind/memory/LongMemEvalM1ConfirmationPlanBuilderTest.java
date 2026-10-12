package com.kama.jchatmind.memory;

import com.kama.jchatmind.memory.LongMemEvalBlindReviewDecisionLoader.Decision;
import com.kama.jchatmind.memory.LongMemEvalBlindReviewDecisionLoader.ResolvedDecision;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongMemEvalM1ConfirmationPlanBuilderTest {

    private static final String CASE_ID = "case-1";
    private static final String USER_ID = "980000000100";
    private static final Set<String> VERIFIED_CASE_IDS = Set.of(CASE_ID, "case-2");
    private static final String SESSION_1 = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION_2 = "22222222-2222-4222-8222-222222222222";
    private static final String MESSAGE_1 = "33333333-3333-4333-8333-333333333333";
    private static final String MESSAGE_2 = "44444444-4444-4444-8444-444444444444";
    private static final Set<String> SESSION_IDS = Set.of(SESSION_1, SESSION_2);

    @Test
    void confirmsEveryPendingCandidateAndIgnoresTerminalRowsDeterministically() {
        List<UserMemoryCandidate> candidates = List.of(
                candidate("55555555-5555-4555-8555-555555555555", SESSION_2, MESSAGE_2,
                        UserMemoryCandidate.STATUS_PENDING),
                candidate("66666666-6666-4666-8666-666666666666", SESSION_1, MESSAGE_1,
                        UserMemoryCandidate.STATUS_PERSISTED),
                candidate("77777777-7777-4777-8777-777777777777", SESSION_1, MESSAGE_1,
                        UserMemoryCandidate.STATUS_PENDING),
                candidate("88888888-8888-4888-8888-888888888888", SESSION_2, MESSAGE_2,
                        UserMemoryCandidate.STATUS_DISCARDED)
        );

        List<ResolvedDecision> first = LongMemEvalM1ConfirmationPlanBuilder.buildPendingConfirmations(
                CASE_ID, USER_ID, VERIFIED_CASE_IDS, SESSION_IDS, candidates
        );
        List<ResolvedDecision> second = LongMemEvalM1ConfirmationPlanBuilder.buildPendingConfirmations(
                CASE_ID, USER_ID, VERIFIED_CASE_IDS, SESSION_IDS, candidates
        );

        assertThat(first).isEqualTo(second).hasSize(2);
        assertThat(first).extracting(ResolvedDecision::candidateId)
                .containsExactly("55555555-5555-4555-8555-555555555555",
                        "77777777-7777-4777-8777-777777777777");
        assertThat(first).extracting(ResolvedDecision::decision).containsOnly(Decision.CONFIRM);
        assertThat(first).extracting(ResolvedDecision::caseId).containsOnly(CASE_ID);
        assertThat(first).extracting(ResolvedDecision::evidenceMessageId)
                .containsExactly(MESSAGE_2, MESSAGE_1);
    }

    @Test
    void allowsNoCandidatesAndRejectsOutOfScopeOrMalformedPendingRows() {
        assertThat(LongMemEvalM1ConfirmationPlanBuilder.buildPendingConfirmations(
                CASE_ID, USER_ID, VERIFIED_CASE_IDS, SESSION_IDS, List.of()
        )).isEmpty();

        UserMemoryCandidate otherOwner =
                candidate("99999999-9999-4999-8999-999999999999", SESSION_1, MESSAGE_1,
                        UserMemoryCandidate.STATUS_PENDING);
        otherOwner.setUserId("980000000101");
        assertThatThrownBy(() -> LongMemEvalM1ConfirmationPlanBuilder.buildPendingConfirmations(
                CASE_ID, USER_ID, VERIFIED_CASE_IDS, SESSION_IDS, List.of(otherOwner)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LongMemEvalM1ConfirmationPlanBuilder.buildPendingConfirmations(
                CASE_ID, USER_ID, VERIFIED_CASE_IDS, SESSION_IDS,
                List.of(candidate("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", MESSAGE_1,
                        UserMemoryCandidate.STATUS_PENDING))
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LongMemEvalM1ConfirmationPlanBuilder.buildPendingConfirmations(
                CASE_ID, USER_ID, VERIFIED_CASE_IDS, SESSION_IDS,
                List.of(candidate("cccccccc-cccc-4ccc-8ccc-cccccccccccc", SESSION_1, null,
                        UserMemoryCandidate.STATUS_PENDING))
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private UserMemoryCandidate candidate(String id, String sessionId, String evidenceId, String status) {
        return UserMemoryCandidate.builder()
                .id(id)
                .userId(USER_ID)
                .sessionId(sessionId)
                .memoryType("PREFERENCE")
                .content("prefers tea")
                .evidence("The user prefers tea.")
                .importance("medium")
                .evidenceMessageId(evidenceId)
                .status(status)
                .build();
    }
}
