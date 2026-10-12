package com.kama.jchatmind.memory;

import com.kama.jchatmind.memory.LongMemEvalReplayHistoryImportPlanBuilder.CaseImportPlan;
import com.kama.jchatmind.memory.LongMemEvalReplayHistoryImportPlanBuilder.HistoryImportPlan;
import com.kama.jchatmind.memory.LongMemEvalReplayHistoryImportPlanBuilder.MessageImportPlan;
import com.kama.jchatmind.memory.LongMemEvalReplayHistoryImportPlanBuilder.SessionImportPlan;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class LongMemEvalReplayHistoryDatabaseImporter {

    private static final String ISOLATED_JDBC_URL =
            "jdbc:postgresql://127.0.0.1:55433/longmemeval_eval";
    private static final String ISOLATED_DATABASE = "longmemeval_eval";
    private static final long VIRTUAL_USER_ID_BASE = 980_000_000_100L;

    private LongMemEvalReplayHistoryDatabaseImporter() {
    }

    static void persist(Connection connection, HistoryImportPlan plan) throws SQLException {
        persist(connection, plan, null);
    }

    static List<ExtractionAttempt> replayHistory(
            Connection connection,
            HistoryImportPlan plan,
            UserMemoryFacadeService memoryService
    ) throws SQLException {
        if (memoryService == null) {
            throw new SQLException("LongMemEval replay requires the production memory extraction service");
        }
        return persist(connection, plan, memoryService);
    }

    private static List<ExtractionAttempt> persist(
            Connection connection,
            HistoryImportPlan plan,
            UserMemoryFacadeService memoryService
    ) throws SQLException {
        requireIsolatedWritableTransaction(connection);
        validatePlan(plan);
        List<ExtractionAttempt> extractionAttempts = new ArrayList<>();

        try (PreparedStatement userStatement = connection.prepareStatement(
                "INSERT INTO public.jchatmind_user (user_id) VALUES (?)");
             PreparedStatement sessionStatement = connection.prepareStatement("""
                     INSERT INTO public.chat_session (id, user_id, title, created_at, updated_at)
                     VALUES (CAST(? AS uuid), ?, 'LongMemEval replay', ?, ?)
                     """);
             PreparedStatement messageStatement = connection.prepareStatement("""
                     INSERT INTO public.chat_message (id, session_id, role, content, created_at, updated_at)
                     VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?)
                     """)) {
            for (CaseImportPlan casePlan : plan.cases()) {
                userStatement.setLong(1, casePlan.virtualUserId());
                requireOneRow(userStatement.executeUpdate(), "virtual user");
                for (SessionImportPlan sessionPlan : casePlan.sessions()) {
                    Timestamp sessionCreatedAt = Timestamp.valueOf(sessionPlan.createdAt());
                    sessionStatement.setString(1, sessionPlan.sessionId());
                    sessionStatement.setString(2, Long.toString(casePlan.virtualUserId()));
                    sessionStatement.setTimestamp(3, sessionCreatedAt);
                    sessionStatement.setTimestamp(4, sessionCreatedAt);
                    requireOneRow(sessionStatement.executeUpdate(), "chat session");
                    for (MessageImportPlan messagePlan : sessionPlan.messages()) {
                        Timestamp messageCreatedAt = Timestamp.valueOf(messagePlan.createdAt());
                        messageStatement.setString(1, messagePlan.messageId());
                        messageStatement.setString(2, messagePlan.sessionId());
                        messageStatement.setString(3, messagePlan.role());
                        messageStatement.setString(4, messagePlan.content());
                        messageStatement.setTimestamp(5, messageCreatedAt);
                        messageStatement.setTimestamp(6, messageCreatedAt);
                        requireOneRow(messageStatement.executeUpdate(), "chat message");
                        if (memoryService != null && ChatMessageDTO.RoleType.USER.getRole().equals(messagePlan.role())) {
                            MemoryExtractionResult result = memoryService.extractMemoryCandidates(
                                    Long.toString(casePlan.virtualUserId()), sessionPlan.sessionId()
                            );
                            extractionAttempts.add(new ExtractionAttempt(
                                    casePlan.caseId(),
                                    sessionPlan.occurrenceIndex(),
                                    sessionPlan.sourceSessionId(),
                                    sessionPlan.sessionId(),
                                    messagePlan.messageIndex(),
                                    result
                            ));
                        }
                    }
                }
            }
        }
        return List.copyOf(extractionAttempts);
    }

    private static void requireIsolatedWritableTransaction(Connection connection) throws SQLException {
        if (connection == null
                || !ISOLATED_JDBC_URL.equals(connection.getMetaData().getURL())
                || !ISOLATED_DATABASE.equals(connection.getCatalog())) {
            throw new SQLException("LongMemEval history import target is not the isolated evaluation database");
        }
        if (connection.getAutoCommit() || connection.isReadOnly()) {
            throw new SQLException("LongMemEval history import requires a writable rollback-capable transaction");
        }
    }

    private static void validatePlan(HistoryImportPlan plan) throws SQLException {
        if (plan == null || !"longmemeval-30-v1".equals(plan.datasetId())
                || plan.cases() == null || plan.cases().isEmpty() || plan.cases().size() > 30) {
            throw new SQLException("LongMemEval history import plan is invalid");
        }

        Set<String> caseIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        Set<String> sessionIds = new HashSet<>();
        Set<String> messageIds = new HashSet<>();
        for (int caseIndex = 0; caseIndex < plan.cases().size(); caseIndex++) {
            CaseImportPlan casePlan = plan.cases().get(caseIndex);
            if (casePlan == null || !StringUtils.hasText(casePlan.caseId())
                    || !caseIds.add(casePlan.caseId())
                    || casePlan.virtualUserId() != VIRTUAL_USER_ID_BASE + caseIndex
                    || !userIds.add(casePlan.virtualUserId()) || casePlan.sessions() == null) {
                throw new SQLException("LongMemEval history import case mapping is invalid");
            }

            for (int sessionIndex = 0; sessionIndex < casePlan.sessions().size(); sessionIndex++) {
                SessionImportPlan sessionPlan = casePlan.sessions().get(sessionIndex);
                if (sessionPlan == null || sessionPlan.occurrenceIndex() != sessionIndex
                        || !StringUtils.hasText(sessionPlan.sourceSessionId())
                        || sessionPlan.createdAt() == null || sessionPlan.messages() == null
                        || !sessionIds.add(requireUuid(sessionPlan.sessionId()))) {
                    throw new SQLException("LongMemEval history import session mapping is invalid");
                }
                for (int messageIndex = 0; messageIndex < sessionPlan.messages().size(); messageIndex++) {
                    MessageImportPlan messagePlan = sessionPlan.messages().get(messageIndex);
                    if (messagePlan == null || messagePlan.messageIndex() != messageIndex
                            || !sessionPlan.sessionId().equals(messagePlan.sessionId())
                            || !StringUtils.hasText(messagePlan.content()) || messagePlan.createdAt() == null
                            || messagePlan.createdAt().isBefore(sessionPlan.createdAt())
                            || !messageIds.add(requireUuid(messagePlan.messageId()))) {
                        throw new SQLException("LongMemEval history import message mapping is invalid");
                    }
                    try {
                        ChatMessageDTO.RoleType.fromRole(messagePlan.role());
                    } catch (IllegalArgumentException exception) {
                        throw new SQLException("LongMemEval history import message role is invalid", exception);
                    }
                }
            }
        }
    }

    private static String requireUuid(String value) throws SQLException {
        if (!StringUtils.hasText(value)) {
            throw new SQLException("LongMemEval history import ID is missing");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new SQLException("LongMemEval history import ID is not a UUID", exception);
        }
    }

    private static void requireOneRow(int rowCount, String entity) throws SQLException {
        if (rowCount != 1) {
            throw new SQLException("LongMemEval history import did not insert one " + entity);
        }
    }

    record ExtractionAttempt(
            String caseId,
            int occurrenceIndex,
            String sourceSessionId,
            String sessionId,
            int messageIndex,
            MemoryExtractionResult result
    ) {
    }
}
