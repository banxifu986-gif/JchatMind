package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.EvidenceMessageRef;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.GoldEvidence;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.ReplayCase;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.ReplayMessage;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.ReplayPlan;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.ReplaySession;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.TargetQuestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongMemEvalReplayHistoryImportPlanBuilderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void createsStableVirtualUsersAndDistinctSessionsForRepeatedOccurrences() {
        ReplayPlan replayPlan = replayPlan();

        LongMemEvalReplayHistoryImportPlanBuilder.HistoryImportPlan first =
                LongMemEvalReplayHistoryImportPlanBuilder.build(replayPlan);
        LongMemEvalReplayHistoryImportPlanBuilder.HistoryImportPlan second =
                LongMemEvalReplayHistoryImportPlanBuilder.build(replayPlan);

        assertThat(first).isEqualTo(second);
        assertThat(first.cases()).extracting(
                LongMemEvalReplayHistoryImportPlanBuilder.CaseImportPlan::virtualUserId
        ).containsExactly(980_000_000_100L, 980_000_000_101L);
        LongMemEvalReplayHistoryImportPlanBuilder.CaseImportPlan firstCase = first.cases().get(0);
        assertThat(firstCase.sessions()).hasSize(2);
        assertThat(firstCase.sessions())
                .extracting(LongMemEvalReplayHistoryImportPlanBuilder.SessionImportPlan::sourceSessionId)
                .containsExactly("session-reused", "session-reused");
        assertThat(firstCase.sessions().get(0).sessionId())
                .isNotEqualTo(firstCase.sessions().get(1).sessionId());
        assertThat(firstCase.sessions().get(0).createdAt())
                .isEqualTo(LocalDateTime.of(2023, 5, 20, 3, 13));
        assertThat(firstCase.sessions().get(0).messages())
                .extracting(LongMemEvalReplayHistoryImportPlanBuilder.MessageImportPlan::createdAt)
                .containsExactly(
                        LocalDateTime.of(2023, 5, 20, 3, 13),
                        LocalDateTime.of(2023, 5, 20, 3, 13).plusNanos(1_000L)
                );
        assertThat(first.toString()).doesNotContain("target question", "gold answer", "has_answer");
    }

    @Test
    void rejectsDatesAndRolesOutsideTheProductionChatMessageContract() {
        ReplayPlan badDate = replayPlanWithSessionDate("2023/05/20 (Sun) 03:13");
        ReplayPlan badRole = replayPlanWithRole("reviewer");

        assertThatThrownBy(() -> LongMemEvalReplayHistoryImportPlanBuilder.build(badDate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session date");
        assertThatThrownBy(() -> LongMemEvalReplayHistoryImportPlanBuilder.build(badRole))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @Test
    @EnabledIfSystemProperty(named = "g3.longmemeval.source.enabled", matches = "true")
    void mapsEverySessionInThePinnedCandidateManifest() throws Exception {
        JsonNode pin = loadPin();
        Path manifestPath = Path.of(
                "target", "memory-eval", "longmemeval-30-v1", "longmemeval-30-v1.candidate.json"
        );
        LongMemEvalCandidateManifestLoader.VerifiedManifest verified =
                LongMemEvalCandidateManifestLoader.load(manifestPath, pin);
        ReplayPlan replayPlan = LongMemEvalReplayPlanBuilder.build(verified.manifest());

        LongMemEvalReplayHistoryImportPlanBuilder.HistoryImportPlan importPlan =
                LongMemEvalReplayHistoryImportPlanBuilder.build(replayPlan);

        assertThat(importPlan.cases()).hasSize(30);
        assertThat(importPlan.cases()).allSatisfy(casePlan -> {
            assertThat(casePlan.sessions()).isNotEmpty();
            assertThat(casePlan.sessions()).allSatisfy(session -> {
                assertThat(session.sessionId()).isNotBlank();
                assertThat(session.createdAt()).isNotNull();
                assertThat(session.messages()).allSatisfy(message -> {
                    assertThat(message.sessionId()).isEqualTo(session.sessionId());
                    assertThat(message.createdAt()).isAfterOrEqualTo(session.createdAt());
                });
            });
        });
        assertThat(recordComponentNames(LongMemEvalReplayHistoryImportPlanBuilder.CaseImportPlan.class))
                .containsExactly("caseId", "virtualUserId", "sessions");
        assertThat(recordComponentNames(LongMemEvalReplayHistoryImportPlanBuilder.MessageImportPlan.class))
                .containsExactly("messageIndex", "messageId", "sessionId", "role", "content", "createdAt");
    }

    private ReplayPlan replayPlan() {
        ReplayCase firstCase = new ReplayCase(
                "case-1",
                "information_extraction",
                List.of(
                        session("case-1", 0, "2023/05/20 (Sat) 03:13", List.of(
                                new ReplayMessage(0, "user", "prefers tea"),
                                new ReplayMessage(1, "assistant", "noted")
                        )),
                        session("case-1", 1, "2023/05/21 (Sun) 07:57", List.of(
                                new ReplayMessage(0, "user", "uses a ceramic cup")
                        ))
                ),
                new TargetQuestion("target question", OBJECT_MAPPER.getNodeFactory().textNode("gold answer"),
                        "2023/05/22 (Mon) 00:31", "single-session-user"),
                new GoldEvidence(List.of("session-reused"),
                        List.of(new EvidenceMessageRef(0, "session-reused", 0)), false)
        );
        ReplayCase secondCase = new ReplayCase(
                "case-2", "knowledge_update", List.of(
                        session("case-2", 0, "2023/05/20 (Sat) 03:13", List.of(
                                new ReplayMessage(0, "user", "likes coffee")
                        ))
                ),
                new TargetQuestion("another target", OBJECT_MAPPER.getNodeFactory().textNode("another answer"),
                        "2023/05/22 (Mon) 00:31", "single-session-user"),
                new GoldEvidence(List.of("session-reused"), List.of(), false)
        );
        return new ReplayPlan("longmemeval-30-v1", List.of(firstCase, secondCase));
    }

    private ReplayPlan replayPlanWithSessionDate(String date) {
        ReplayPlan plan = replayPlan();
        ReplayCase firstCase = plan.cases().get(0);
        ReplaySession session = firstCase.sessions().get(0);
        ReplaySession invalidSession = new ReplaySession(
                session.occurrenceIndex(), session.occurrenceKey(), session.sourceSessionId(), date, session.messages()
        );
        ReplayCase invalidCase = new ReplayCase(
                firstCase.caseId(), firstCase.categoryId(),
                List.of(invalidSession, firstCase.sessions().get(1)), firstCase.target(), firstCase.gold()
        );
        return new ReplayPlan(plan.datasetId(), List.of(invalidCase, plan.cases().get(1)));
    }

    private ReplayPlan replayPlanWithRole(String role) {
        ReplayPlan plan = replayPlan();
        ReplayCase firstCase = plan.cases().get(0);
        ReplaySession session = firstCase.sessions().get(0);
        ReplayMessage message = new ReplayMessage(0, role, "prefers tea");
        ReplaySession invalidSession = new ReplaySession(
                session.occurrenceIndex(), session.occurrenceKey(), session.sourceSessionId(), session.date(),
                List.of(message, session.messages().get(1))
        );
        ReplayCase invalidCase = new ReplayCase(
                firstCase.caseId(), firstCase.categoryId(),
                List.of(invalidSession, firstCase.sessions().get(1)), firstCase.target(), firstCase.gold()
        );
        return new ReplayPlan(plan.datasetId(), List.of(invalidCase, plan.cases().get(1)));
    }

    private ReplaySession session(String caseId, int index, String date, List<ReplayMessage> messages) {
        return new ReplaySession(index, caseId + "#" + index, "session-reused", date, messages);
    }

    private List<String> recordComponentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private JsonNode loadPin() throws Exception {
        try (InputStream inputStream = new ClassPathResource(
                "memory-eval/longmemeval-source-pin-v1.json"
        ).getInputStream()) {
            return OBJECT_MAPPER.readTree(inputStream);
        }
    }
}
