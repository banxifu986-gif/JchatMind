package com.kama.jchatmind.memory;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LongMemEvalBlindReviewDecisionApplierTest {

    @Test
    void appliesBlindDecisionsUsingIsolatedVirtualUserScopeAndRestoresCallerScope() {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(123L);
        requestScopeData.setLogin(false);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        List<String> calls = new ArrayList<>();
        doAnswer(invocation -> {
            calls.add("confirm:" + invocation.getArgument(0) + ":" + requestScopeData.getUserId());
            assertThat(requestScopeData.isLogin()).isTrue();
            return null;
        }).when(userMemoryFacadeService).confirmUserMemoryCandidate("candidate-1");
        doAnswer(invocation -> {
            calls.add("discard:" + invocation.getArgument(0) + ":" + requestScopeData.getUserId());
            assertThat(requestScopeData.isLogin()).isTrue();
            return null;
        }).when(userMemoryFacadeService).discardUserMemoryCandidate("candidate-2");

        LongMemEvalBlindReviewDecisionApplier.apply(
                decisions(),
                Map.of("case-1", "900000000001", "case-2", "900000000002"),
                requestScopeData,
                userMemoryFacadeService
        );

        assertThat(calls).containsExactly(
                "confirm:candidate-1:900000000001",
                "discard:candidate-2:900000000002"
        );
        assertThat(requestScopeData.getUserId()).isEqualTo(123L);
        assertThat(requestScopeData.isLogin()).isFalse();
    }

    @Test
    void rejectsMissingOrSharedVirtualUserMapping() {
        RequestScopeData requestScopeData = new RequestScopeData();
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);

        assertThatThrownBy(() -> LongMemEvalBlindReviewDecisionApplier.apply(
                decisions(),
                Map.of("case-1", "900000000001"),
                requestScopeData,
                userMemoryFacadeService
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LongMemEvalBlindReviewDecisionApplier.apply(
                decisions(),
                Map.of("case-1", "900000000001", "case-2", "900000000001"),
                requestScopeData,
                userMemoryFacadeService
        )).isInstanceOf(IllegalArgumentException.class);
        verify(userMemoryFacadeService, never()).confirmUserMemoryCandidate("candidate-1");
    }

    @Test
    void restoresCallerScopeAndStopsWhenApplyingADecisionFails() {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(123L);
        requestScopeData.setLogin(true);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        doThrow(new IllegalStateException("candidate status changed"))
                .when(userMemoryFacadeService).confirmUserMemoryCandidate("candidate-1");

        assertThatThrownBy(() -> LongMemEvalBlindReviewDecisionApplier.apply(
                decisions(),
                Map.of("case-1", "900000000001", "case-2", "900000000002"),
                requestScopeData,
                userMemoryFacadeService
        )).isInstanceOf(IllegalStateException.class);

        assertThat(requestScopeData.getUserId()).isEqualTo(123L);
        assertThat(requestScopeData.isLogin()).isTrue();
        verify(userMemoryFacadeService, never()).discardUserMemoryCandidate("candidate-2");
    }

    private List<LongMemEvalBlindReviewDecisionLoader.ResolvedDecision> decisions() {
        return List.of(
                new LongMemEvalBlindReviewDecisionLoader.ResolvedDecision(
                        "review-1", "case-1", "candidate-1", "message-1",
                        LongMemEvalBlindReviewDecisionLoader.Decision.CONFIRM
                ),
                new LongMemEvalBlindReviewDecisionLoader.ResolvedDecision(
                        "review-2", "case-2", "candidate-2", "message-2",
                        LongMemEvalBlindReviewDecisionLoader.Decision.DISCARD
                )
        );
    }
}
