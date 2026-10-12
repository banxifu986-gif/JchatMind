package com.kama.jchatmind.memory;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class LongMemEvalReplayHistoryImportPlanBuilder {

    private static final long VIRTUAL_USER_ID_BASE = 980_000_000_100L;
    private static final DateTimeFormatter SESSION_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("uuuu/MM/dd (EEE) HH:mm", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT);

    private LongMemEvalReplayHistoryImportPlanBuilder() {
    }

    static HistoryImportPlan build(LongMemEvalReplayPlanBuilder.ReplayPlan replayPlan) {
        if (replayPlan == null || !"longmemeval-30-v1".equals(replayPlan.datasetId())
                || replayPlan.cases() == null || replayPlan.cases().isEmpty()
                || replayPlan.cases().size() > 30) {
            throw new IllegalArgumentException("LongMemEval replay plan 无效");
        }

        List<CaseImportPlan> cases = new ArrayList<>(replayPlan.cases().size());
        Set<String> caseIds = new HashSet<>();
        Set<String> sessionIds = new HashSet<>();
        Set<String> messageIds = new HashSet<>();
        for (int caseIndex = 0; caseIndex < replayPlan.cases().size(); caseIndex++) {
            LongMemEvalReplayPlanBuilder.ReplayCase replayCase = replayPlan.cases().get(caseIndex);
            if (replayCase == null || !StringUtils.hasText(replayCase.caseId())
                    || !caseIds.add(replayCase.caseId()) || replayCase.sessions() == null) {
                throw new IllegalArgumentException("LongMemEval replay case 重复或无效");
            }

            long virtualUserId = Math.addExact(VIRTUAL_USER_ID_BASE, caseIndex);
            List<SessionImportPlan> sessions = new ArrayList<>(replayCase.sessions().size());
            for (int sessionIndex = 0; sessionIndex < replayCase.sessions().size(); sessionIndex++) {
                LongMemEvalReplayPlanBuilder.ReplaySession replaySession = replayCase.sessions().get(sessionIndex);
                if (replaySession == null || replaySession.occurrenceIndex() != sessionIndex
                        || !((replayCase.caseId() + "#" + sessionIndex).equals(replaySession.occurrenceKey()))
                        || !StringUtils.hasText(replaySession.sourceSessionId())
                        || replaySession.messages() == null) {
                    throw new IllegalArgumentException("LongMemEval replay session occurrence 无效: "
                            + replayCase.caseId());
                }
                LocalDateTime sessionCreatedAt = parseSessionDate(
                        replaySession.date(), replayCase.caseId(), sessionIndex
                );
                String sessionId = deterministicId(
                        replayPlan.datasetId(), replayCase.caseId(), "session", sessionIndex,
                        replaySession.sourceSessionId()
                );
                if (!sessionIds.add(sessionId)) {
                    throw new IllegalArgumentException("LongMemEval replay session UUID 冲突");
                }

                List<MessageImportPlan> messages = new ArrayList<>(replaySession.messages().size());
                for (int messageIndex = 0; messageIndex < replaySession.messages().size(); messageIndex++) {
                    LongMemEvalReplayPlanBuilder.ReplayMessage replayMessage =
                            replaySession.messages().get(messageIndex);
                    if (replayMessage == null || replayMessage.messageIndex() != messageIndex
                            || !StringUtils.hasText(replayMessage.content())) {
                        throw new IllegalArgumentException("LongMemEval replay message 无效: "
                                + replayCase.caseId());
                    }
                    try {
                        ChatMessageDTO.RoleType.fromRole(replayMessage.role());
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("LongMemEval replay message role 无效: "
                                + replayCase.caseId(), exception);
                    }

                    String messageId = deterministicId(
                            replayPlan.datasetId(), replayCase.caseId(), "message", sessionIndex,
                            Integer.toString(messageIndex)
                    );
                    if (!messageIds.add(messageId)) {
                        throw new IllegalArgumentException("LongMemEval replay message UUID 冲突");
                    }
                    messages.add(new MessageImportPlan(
                            messageIndex,
                            messageId,
                            sessionId,
                            replayMessage.role(),
                            replayMessage.content(),
                            sessionCreatedAt.plusNanos(messageIndex * 1_000L)
                    ));
                }
                sessions.add(new SessionImportPlan(
                        sessionIndex,
                        replaySession.sourceSessionId(),
                        sessionId,
                        sessionCreatedAt,
                        List.copyOf(messages)
                ));
            }
            cases.add(new CaseImportPlan(replayCase.caseId(), virtualUserId, List.copyOf(sessions)));
        }
        return new HistoryImportPlan(replayPlan.datasetId(), List.copyOf(cases));
    }

    private static LocalDateTime parseSessionDate(String value, String caseId, int occurrenceIndex) {
        try {
            return LocalDateTime.parse(value, SESSION_DATE_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("LongMemEval session date 无法解析: "
                    + caseId + " occurrence=" + occurrenceIndex, exception);
        }
    }

    private static String deterministicId(
            String datasetId,
            String caseId,
            String entityType,
            int occurrenceIndex,
            String entityIndex
    ) {
        String value = String.join("\u0000", datasetId, caseId, entityType,
                Integer.toString(occurrenceIndex), entityIndex);
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    record HistoryImportPlan(String datasetId, List<CaseImportPlan> cases) {
    }

    record CaseImportPlan(String caseId, long virtualUserId, List<SessionImportPlan> sessions) {
    }

    record SessionImportPlan(
            int occurrenceIndex,
            String sourceSessionId,
            String sessionId,
            LocalDateTime createdAt,
            List<MessageImportPlan> messages
    ) {
    }

    record MessageImportPlan(
            int messageIndex,
            String messageId,
            String sessionId,
            String role,
            String content,
            LocalDateTime createdAt
    ) {
    }
}
