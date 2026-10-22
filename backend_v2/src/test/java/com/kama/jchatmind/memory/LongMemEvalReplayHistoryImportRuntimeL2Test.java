package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.ChatSessionConverter;
import com.kama.jchatmind.event.listener.ChatSessionExecutionCoordinator;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.memory.LongMemEvalReplayHistoryImportPlanBuilder.CaseImportPlan;
import com.kama.jchatmind.memory.LongMemEvalReplayHistoryImportPlanBuilder.HistoryImportPlan;
import com.kama.jchatmind.memory.LongMemEvalReplayHistoryImportPlanBuilder.MessageImportPlan;
import com.kama.jchatmind.memory.LongMemEvalReplayHistoryImportPlanBuilder.SessionImportPlan;
import com.kama.jchatmind.memory.LongMemEvalBlindReviewDecisionLoader.ResolvedDecision;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.EvidenceMessageRef;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.GoldEvidence;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.ReplayCase;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.ReplayMessage;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.ReplayPlan;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.ReplaySession;
import com.kama.jchatmind.memory.LongMemEvalReplayPlanBuilder.TargetQuestion;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import com.kama.jchatmind.service.impl.ChatMessageFacadeServiceImpl;
import com.kama.jchatmind.service.impl.ChatSessionFacadeServiceImpl;
import com.kama.jchatmind.service.impl.UserMemoryFacadeServiceImpl;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongMemEvalReplayHistoryImportRuntimeL2Test {

    private static final String ISOLATED_JDBC_URL =
            "jdbc:postgresql://127.0.0.1:55433/longmemeval_eval";
    private static final String ISOLATED_DATABASE = "longmemeval_eval";

    @Test
    void rejectsWrongDatabaseOrAutocommitBeforePreparingWrites() throws Exception {
        HistoryImportPlan importPlan = LongMemEvalReplayHistoryImportPlanBuilder.build(plan());
        Connection wrongDatabase = mock(Connection.class);
        java.sql.DatabaseMetaData wrongMetadata = mock(java.sql.DatabaseMetaData.class);
        when(wrongDatabase.getMetaData()).thenReturn(wrongMetadata);
        when(wrongMetadata.getURL()).thenReturn("jdbc:postgresql://127.0.0.1:55432/rag_eval");
        when(wrongDatabase.getCatalog()).thenReturn("rag_eval");

        assertThatThrownBy(() -> LongMemEvalReplayHistoryDatabaseImporter.persist(wrongDatabase, importPlan))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("isolated evaluation database");
        verify(wrongDatabase, never()).prepareStatement(anyString());

        Connection autocommit = mock(Connection.class);
        java.sql.DatabaseMetaData metadata = mock(java.sql.DatabaseMetaData.class);
        when(autocommit.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(ISOLATED_JDBC_URL);
        when(autocommit.getCatalog()).thenReturn(ISOLATED_DATABASE);
        when(autocommit.getAutoCommit()).thenReturn(true);
        when(autocommit.isReadOnly()).thenReturn(false);

        assertThatThrownBy(() -> LongMemEvalReplayHistoryDatabaseImporter.persist(autocommit, importPlan))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("rollback-capable transaction");
        verify(autocommit, never()).prepareStatement(anyString());
    }

    @Test
    void productionChatFacadeCandidateFeedsBlindReviewWithoutPrivateLinks() {
        HistoryImportPlan importPlan = LongMemEvalReplayHistoryImportPlanBuilder.build(plan());
        CaseImportPlan casePlan = importPlan.cases().get(0);
        SessionImportPlan firstSession = casePlan.sessions().get(0);
        String userId = Long.toString(casePlan.virtualUserId());
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(casePlan.virtualUserId());
        requestScopeData.setLogin(true);

        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        when(chatSessionMapper.selectByIdAndUserId(firstSession.sessionId(), userId)).thenReturn(
                ChatSession.builder()
                        .id(firstSession.sessionId())
                        .userId(userId)
                        .title("LongMemEval replay")
                        .createdAt(firstSession.createdAt())
                        .updatedAt(firstSession.createdAt())
                        .build()
        );
        List<ChatMessage> messages = firstSession.messages().stream()
                .map(message -> ChatMessage.builder()
                        .id(message.messageId())
                        .sessionId(message.sessionId())
                        .role(message.role())
                        .content(message.content())
                        .createdAt(message.createdAt())
                        .updatedAt(message.createdAt())
                        .build())
                .toList();
        when(chatMessageMapper.countUserMessagesBySessionId(firstSession.sessionId())).thenReturn(1);
        when(chatMessageMapper.selectBySessionIdRecently(firstSession.sessionId(), 8)).thenReturn(messages);

        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectActiveByUserId(userId)).thenReturn(List.of());
        when(userMemoryMapper.selectByUserIdAndContent(userId, "prefers tea")).thenReturn(null);
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.selectByUserIdAndContent(userId, "prefers tea")).thenReturn(null);
        String candidateId = "b18c8289-0c66-4ba5-9c18-9bb7aa012345";
        when(candidateMapper.insert(any(UserMemoryCandidate.class))).thenAnswer(invocation -> {
            UserMemoryCandidate inserted = invocation.getArgument(0);
            inserted.setId(candidateId);
            return 1;
        });

        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ChatSessionFacadeServiceImpl chatSessionService = new ChatSessionFacadeServiceImpl(
                chatSessionMapper,
                new ChatSessionConverter(new ObjectMapper()),
                requestScopeData,
                mock(ChatSessionExecutionCoordinator.class),
                eventPublisher
        );
        ChatMessageFacadeServiceImpl chatMessageService = new ChatMessageFacadeServiceImpl(
                chatMessageMapper,
                new ChatMessageConverter(new ObjectMapper()),
                chatSessionService,
                eventPublisher,
                requestScopeData
        );
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClientRegistry> registryProvider = mock(ObjectProvider.class);
        ChatClientRegistry chatClientRegistry = new ChatClientRegistry(
                Map.of("deepseek-chat", extractionClient())
        );
        when(registryProvider.getIfAvailable()).thenReturn(chatClientRegistry);
        UserMemoryFacadeServiceImpl memoryService = new UserMemoryFacadeServiceImpl(
                userMemoryMapper,
                candidateMapper,
                chatMessageService,
                requestScopeData,
                registryProvider,
                null
        );

        assertThat(memoryService.extractMemoryCandidates(userId, firstSession.sessionId()))
                .isEqualTo(MemoryExtractionResult.EXTRACTED);

        ArgumentCaptor<UserMemoryCandidate> candidate = ArgumentCaptor.forClass(UserMemoryCandidate.class);
        verify(candidateMapper).insert(candidate.capture());
        assertThat(candidate.getValue().getStatus()).isEqualTo(UserMemoryCandidate.STATUS_PENDING);
        assertThat(candidate.getValue().getEvidenceMessageId())
                .isEqualTo(firstSession.messages().get(0).messageId());
        assertThat(candidate.getValue().getEvidence()).isEqualTo("prefers tea");
        verify(chatMessageMapper).countUserMessagesBySessionId(firstSession.sessionId());
        verify(chatMessageMapper).selectBySessionIdRecently(firstSession.sessionId(), 8);

        LongMemEvalBlindReviewPackageBuilder.Artifacts reviewArtifacts =
                LongMemEvalBlindReviewPackageBuilder.build(
                        "longmemeval-30-v1",
                        UUID.fromString("19fcd271-75d1-448d-9426-53f84a947ef1"),
                        Set.of("case-l2"),
                        List.of(new LongMemEvalBlindReviewPackageBuilder.CandidateForReview(
                                "case-l2",
                                candidate.getValue().getId(),
                                candidate.getValue().getMemoryType(),
                                candidate.getValue().getContent(),
                                candidate.getValue().getImportance(),
                                candidate.getValue().getEvidenceMessageId(),
                                candidate.getValue().getEvidence(),
                                candidate.getValue().getStatus()
                        ))
                );
        JsonNode reviewerItem = reviewArtifacts.reviewerPacket().path("items").get(0);
        assertThat(reviewArtifacts.reviewerPacket().path("items")).hasSize(1);
        assertThat(reviewerItem.path("content").asText()).isEqualTo("prefers tea");
        assertThat(reviewerItem.path("evidenceText").asText()).isEqualTo("prefers tea");
        assertThat(reviewerItem.has("caseId")).isFalse();
        assertThat(reviewerItem.has("candidateId")).isFalse();
        assertThat(reviewerItem.has("evidenceMessageId")).isFalse();
        assertThat(reviewArtifacts.privateMapping().path("items").get(0).path("candidateId").asText())
                .isEqualTo(candidateId);

        ObjectNode decisionFile = new ObjectMapper().createObjectNode();
        decisionFile.put("schemaVersion", 1);
        decisionFile.put("batchId", reviewArtifacts.privateMapping().path("batchId").asText());
        decisionFile.putArray("decisions").addObject()
                .put("reviewItemId", reviewerItem.path("reviewItemId").asText())
                .put("decision", "CONFIRM");
        List<ResolvedDecision> resolvedDecisions = LongMemEvalBlindReviewDecisionLoader.resolve(
                reviewArtifacts.privateMapping(), decisionFile
        );
        assertThat(resolvedDecisions).hasSize(1);
        assertThat(resolvedDecisions.get(0).candidateId()).isEqualTo(candidateId);
        when(candidateMapper.selectByIdAndUserId(candidateId, userId)).thenReturn(candidate.getValue());
        when(candidateMapper.markPersistedIfPending(candidateId, userId)).thenReturn(1);
        when(userMemoryMapper.insert(any(UserMemory.class))).thenReturn(1);
        requestScopeData.setUserId(123L);
        requestScopeData.setLogin(false);

        LongMemEvalBlindReviewDecisionApplier.apply(
                resolvedDecisions,
                Map.of("case-l2", userId),
                requestScopeData,
                memoryService
        );

        ArgumentCaptor<UserMemory> confirmedMemory = ArgumentCaptor.forClass(UserMemory.class);
        verify(userMemoryMapper).insert(confirmedMemory.capture());
        assertThat(confirmedMemory.getValue().getContent()).isEqualTo("prefers tea");
        assertThat(confirmedMemory.getValue().getEvidenceMessageId())
                .isEqualTo(firstSession.messages().get(0).messageId());
        assertThat(confirmedMemory.getValue().getExpiresAt()).isNotNull();
        assertThat(requestScopeData.getUserId()).isEqualTo(123L);
        assertThat(requestScopeData.isLogin()).isFalse();
    }

    @Test
    void writesOnlyHistoryPlanRowsAndLeavesTransactionControlToCaller() throws Exception {
        HistoryImportPlan importPlan = LongMemEvalReplayHistoryImportPlanBuilder.build(plan());
        Connection connection = mock(Connection.class);
        java.sql.DatabaseMetaData metadata = mock(java.sql.DatabaseMetaData.class);
        PreparedStatement userStatement = mock(PreparedStatement.class);
        PreparedStatement sessionStatement = mock(PreparedStatement.class);
        PreparedStatement messageStatement = mock(PreparedStatement.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(ISOLATED_JDBC_URL);
        when(connection.getCatalog()).thenReturn(ISOLATED_DATABASE);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.isReadOnly()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("public.jchatmind_user")) {
                return userStatement;
            }
            if (sql.contains("public.chat_session")) {
                return sessionStatement;
            }
            if (sql.contains("public.chat_message")) {
                return messageStatement;
            }
            throw new java.sql.SQLException("Unexpected evaluation importer SQL");
        });
        when(userStatement.executeUpdate()).thenReturn(1);
        when(sessionStatement.executeUpdate()).thenReturn(1);
        when(messageStatement.executeUpdate()).thenReturn(1);

        LongMemEvalReplayHistoryDatabaseImporter.persist(connection, importPlan);

        verify(userStatement).setLong(1, 980_000_000_100L);
        verify(userStatement, times(1)).executeUpdate();
        verify(sessionStatement, times(2)).executeUpdate();
        verify(messageStatement, times(4)).executeUpdate();
        ArgumentCaptor<String> messageContent = ArgumentCaptor.forClass(String.class);
        verify(messageStatement, times(4)).setString(eq(4), messageContent.capture());
        assertThat(messageContent.getAllValues()).containsExactly(
                "prefers tea", "noted", "uses a ceramic cup", "understood"
        );
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(3)).prepareStatement(sql.capture());
        assertThat(sql.getAllValues())
                .anySatisfy(statement -> assertThat(statement).contains("public.jchatmind_user"))
                .anySatisfy(statement -> assertThat(statement).contains("public.chat_session"))
                .anySatisfy(statement -> assertThat(statement).contains("public.chat_message"));
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void invokesMemoryExtractionAfterEachUserMessageBeforeWritingLaterTurns() throws Exception {
        HistoryImportPlan importPlan = LongMemEvalReplayHistoryImportPlanBuilder.build(plan());
        Connection connection = mock(Connection.class);
        java.sql.DatabaseMetaData metadata = mock(java.sql.DatabaseMetaData.class);
        PreparedStatement userStatement = mock(PreparedStatement.class);
        PreparedStatement sessionStatement = mock(PreparedStatement.class);
        PreparedStatement messageStatement = mock(PreparedStatement.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(ISOLATED_JDBC_URL);
        when(connection.getCatalog()).thenReturn(ISOLATED_DATABASE);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.isReadOnly()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("public.jchatmind_user")) {
                return userStatement;
            }
            if (sql.contains("public.chat_session")) {
                return sessionStatement;
            }
            if (sql.contains("public.chat_message")) {
                return messageStatement;
            }
            throw new java.sql.SQLException("Unexpected evaluation importer SQL");
        });
        when(userStatement.executeUpdate()).thenReturn(1);
        when(sessionStatement.executeUpdate()).thenReturn(1);
        when(messageStatement.executeUpdate()).thenReturn(1);
        UserMemoryFacadeService memoryService = mock(UserMemoryFacadeService.class);
        when(memoryService.extractMemoryCandidates(anyString(), anyString()))
                .thenReturn(MemoryExtractionResult.EXTRACTED);

        List<LongMemEvalReplayHistoryDatabaseImporter.ExtractionAttempt> attempts =
                LongMemEvalReplayHistoryDatabaseImporter.replayHistory(connection, importPlan, memoryService);

        CaseImportPlan casePlan = importPlan.cases().get(0);
        assertThat(casePlan.sessions()).hasSize(2);
        assertThat(attempts).extracting(
                LongMemEvalReplayHistoryDatabaseImporter.ExtractionAttempt::result
        ).containsExactly(MemoryExtractionResult.EXTRACTED, MemoryExtractionResult.EXTRACTED);
        assertThat(attempts).extracting(
                LongMemEvalReplayHistoryDatabaseImporter.ExtractionAttempt::sessionId
        ).containsExactly(
                casePlan.sessions().get(0).sessionId(),
                casePlan.sessions().get(1).sessionId()
        );
        InOrder order = inOrder(messageStatement, memoryService);
        order.verify(messageStatement).executeUpdate();
        order.verify(memoryService).extractMemoryCandidates(
                Long.toString(casePlan.virtualUserId()), casePlan.sessions().get(0).sessionId()
        );
        order.verify(messageStatement).executeUpdate();
        order.verify(messageStatement).executeUpdate();
        order.verify(memoryService).extractMemoryCandidates(
                Long.toString(casePlan.virtualUserId()), casePlan.sessions().get(1).sessionId()
        );
        order.verify(messageStatement).executeUpdate();
        verify(messageStatement, times(4)).executeUpdate();
        verify(memoryService, times(2)).extractMemoryCandidates(anyString(), anyString());
        verify(connection, never()).commit();
    }

    @Test
    @EnabledIfSystemProperty(named = "g3.longmemeval.replay.history.l2.enabled", matches = "true")
    void extractsEvidenceLinkedCandidateFromImportedHistoryInRollbackTransaction() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(ISOLATED_JDBC_URL);
        dataSource.setUsername("longmemeval_eval");
        dataSource.setPassword("");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertThat(connection.getMetaData().getURL()).isEqualTo(ISOLATED_JDBC_URL);
                assertThat(connection.getCatalog()).isEqualTo(ISOLATED_DATABASE);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS public.agent_execution_turn_message ("
                            + "chat_message_id UUID PRIMARY KEY, session_id UUID NOT NULL, sequence_no BIGINT NOT NULL)");
                }
                HistoryImportPlan importPlan = LongMemEvalReplayHistoryImportPlanBuilder.build(plan());

                SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
                factoryBean.setDataSource(new SingleConnectionDataSource(connection, true));
                factoryBean.setTypeHandlers(new PgVectorTypeHandler());
                factoryBean.setMapperLocations(
                        new ClassPathResource("mapper/ChatSessionMapper.xml"),
                        new ClassPathResource("mapper/ChatMessageMapper.xml"),
                        new ClassPathResource("mapper/UserMemoryMapper.xml"),
                        new ClassPathResource("mapper/UserMemoryCandidateMapper.xml")
                );
                SqlSessionFactory sessionFactory = factoryBean.getObject();
                assertThat(sessionFactory).isNotNull();
                try (SqlSession session = sessionFactory.openSession(false)) {
                    ChatSessionMapper sessionMapper = session.getMapper(ChatSessionMapper.class);
                    ChatMessageMapper messageMapper = session.getMapper(ChatMessageMapper.class);
                    CaseImportPlan casePlan = importPlan.cases().get(0);

                    RequestScopeData requestScopeData = new RequestScopeData();
                    requestScopeData.setUserId(casePlan.virtualUserId());
                    requestScopeData.setLogin(true);
                    ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
                    ChatSessionFacadeServiceImpl chatSessionService = new ChatSessionFacadeServiceImpl(
                            sessionMapper,
                            new ChatSessionConverter(new ObjectMapper()),
                            requestScopeData,
                            mock(ChatSessionExecutionCoordinator.class),
                            eventPublisher
                    );
                    ChatMessageFacadeServiceImpl chatMessageService = new ChatMessageFacadeServiceImpl(
                            messageMapper,
                            new ChatMessageConverter(new ObjectMapper()),
                            chatSessionService,
                            eventPublisher,
                            requestScopeData
                    );
                    @SuppressWarnings("unchecked")
                    ObjectProvider<ChatClientRegistry> registryProvider = mock(ObjectProvider.class);
                    ChatClientRegistry chatClientRegistry = new ChatClientRegistry(
                            Map.of("deepseek-chat", extractionClient())
                    );
                    when(registryProvider.getIfAvailable()).thenReturn(chatClientRegistry);
                    UserMemoryMapper userMemoryMapper = session.getMapper(UserMemoryMapper.class);
                    UserMemoryCandidateMapper candidateMapper = session.getMapper(UserMemoryCandidateMapper.class);
                    String userId = Long.toString(casePlan.virtualUserId());
                    UserMemoryFacadeServiceImpl memoryService = new UserMemoryFacadeServiceImpl(
                            userMemoryMapper,
                            candidateMapper,
                            chatMessageService,
                            requestScopeData,
                            registryProvider,
                            null
                    );

                    List<LongMemEvalReplayHistoryDatabaseImporter.ExtractionAttempt> attempts =
                            LongMemEvalReplayHistoryDatabaseImporter.replayHistory(
                                    connection, importPlan, memoryService
                            );
                    assertThat(attempts).hasSize(2);
                    assertThat(attempts).extracting(
                            LongMemEvalReplayHistoryDatabaseImporter.ExtractionAttempt::result
                    ).containsOnly(MemoryExtractionResult.EXTRACTED);

                    assertThat(casePlan.sessions()).extracting(SessionImportPlan::sessionId)
                            .doesNotHaveDuplicates();
                    for (SessionImportPlan sessionPlan : casePlan.sessions()) {
                        ChatSession persistedSession = sessionMapper.selectByIdAndUserId(
                                sessionPlan.sessionId(), Long.toString(casePlan.virtualUserId())
                        );
                        assertThat(persistedSession).isNotNull();
                        assertThat(persistedSession.getUserId()).isEqualTo(Long.toString(casePlan.virtualUserId()));
                        List<ChatMessage> persistedMessages = messageMapper.selectBySessionIdRecently(
                                sessionPlan.sessionId(), 8
                        );
                        assertThat(persistedMessages).extracting(ChatMessage::getRole)
                                .containsExactlyElementsOf(sessionPlan.messages().stream()
                                        .map(MessageImportPlan::role).toList());
                        assertThat(persistedMessages).extracting(ChatMessage::getContent)
                                .containsExactlyElementsOf(sessionPlan.messages().stream()
                                        .map(MessageImportPlan::content).toList());
                        assertThat(messageMapper.countUserMessagesBySessionId(sessionPlan.sessionId()))
                                .isEqualTo(1);
                        assertThat(persistedMessages).extracting(ChatMessage::getCreatedAt)
                                .containsExactlyElementsOf(sessionPlan.messages().stream()
                                        .map(message -> Timestamp.valueOf(message.createdAt()).toLocalDateTime())
                                        .toList());
                    }
                    SessionImportPlan firstSession = casePlan.sessions().get(0);
                    List<UserMemoryCandidate> candidates = candidateMapper.selectByUserId(userId);
                    SessionImportPlan secondSession = casePlan.sessions().get(1);
                    assertThat(candidates).hasSize(2).allSatisfy(candidate ->
                            assertThat(candidate.getStatus()).isEqualTo(UserMemoryCandidate.STATUS_PENDING)
                    );
                    assertThat(candidates).anySatisfy(candidate -> {
                        assertThat(candidate.getSessionId()).isEqualTo(firstSession.sessionId());
                        assertThat(candidate.getEvidenceMessageId())
                                .isEqualTo(firstSession.messages().get(0).messageId());
                        assertThat(candidate.getEvidence()).isEqualTo(firstSession.messages().get(0).content());
                    });
                    assertThat(candidates).anySatisfy(candidate -> {
                        assertThat(candidate.getSessionId()).isEqualTo(secondSession.sessionId());
                        assertThat(candidate.getEvidenceMessageId())
                                .isEqualTo(secondSession.messages().get(0).messageId());
                        assertThat(candidate.getEvidence()).isEqualTo(secondSession.messages().get(0).content());
                    });
                    assertThat(userMemoryMapper.selectByUserId(userId)).isEmpty();

                    Set<String> allowedSessionIds = casePlan.sessions().stream()
                            .map(SessionImportPlan::sessionId)
                            .collect(Collectors.toSet());
                    List<ResolvedDecision> m1Confirmations =
                            LongMemEvalM1ConfirmationPlanBuilder.buildPendingConfirmations(
                                    casePlan.caseId(),
                                    userId,
                                    Set.of(casePlan.caseId()),
                                    allowedSessionIds,
                                    candidates
                            );
                    assertThat(m1Confirmations).hasSize(2);
                    LongMemEvalBlindReviewDecisionApplier.apply(
                            m1Confirmations,
                            Map.of(casePlan.caseId(), userId),
                            requestScopeData,
                            memoryService
                    );

                    List<UserMemoryCandidate> persistedCandidates = candidateMapper.selectByUserId(userId);
                    assertThat(persistedCandidates).hasSize(2).extracting(UserMemoryCandidate::getStatus)
                            .containsOnly(UserMemoryCandidate.STATUS_PERSISTED);
                    List<UserMemory> confirmedMemories = userMemoryMapper.selectByUserId(userId);
                    assertThat(confirmedMemories).hasSize(2);
                    assertThat(confirmedMemories).allSatisfy(memory ->
                            assertThat(memory.getExpiresAt()).isNotNull()
                    );
                    assertThat(confirmedMemories).anySatisfy(memory -> {
                        assertThat(memory.getSessionId()).isEqualTo(firstSession.sessionId());
                        assertThat(memory.getContent()).isEqualTo("prefers tea");
                        assertThat(memory.getEvidenceMessageId())
                                .isEqualTo(firstSession.messages().get(0).messageId());
                    });
                    assertThat(confirmedMemories).anySatisfy(memory -> {
                        assertThat(memory.getSessionId()).isEqualTo(secondSession.sessionId());
                        assertThat(memory.getContent()).isEqualTo("uses a ceramic cup");
                        assertThat(memory.getEvidenceMessageId())
                                .isEqualTo(secondSession.messages().get(0).messageId());
                    });
                    session.rollback();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private ReplayPlan plan() {
        ReplayCase replayCase = new ReplayCase(
                "case-l2",
                "information_extraction",
                List.of(
                        replaySession(0, "2023/05/20 (Sat) 03:13", List.of(
                                new ReplayMessage(0, "user", "prefers tea"),
                                new ReplayMessage(1, "assistant", "noted")
                        )),
                        replaySession(1, "2023/05/21 (Sun) 07:57", List.of(
                                new ReplayMessage(0, "user", "uses a ceramic cup"),
                                new ReplayMessage(1, "assistant", "understood")
                        ))
                ),
                new TargetQuestion("excluded target", null, "2023/05/22 (Mon) 00:31", "single-session-user"),
                new GoldEvidence(List.of("source-session-reused"),
                        List.of(new EvidenceMessageRef(0, "source-session-reused", 0)), false)
        );
        return new ReplayPlan("longmemeval-30-v1", List.of(replayCase));
    }

    private ReplaySession replaySession(int index, String date, List<ReplayMessage> messages) {
        return new ReplaySession(index, "case-l2#" + index, "source-session-reused", date, messages);
    }

    private ChatClient extractionClient() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        String teaResponse = extractionResponse("prefers tea");
        String cupResponse = extractionResponse("uses a ceramic cup");
        AtomicReference<String> response = new AtomicReference<>(teaResponse);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            response.set(prompt.contains("uses a ceramic cup") ? cupResponse : teaResponse);
            return requestSpec;
        });
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenAnswer(invocation -> response.get());
        return chatClient;
    }

    private String extractionResponse(String content) {
        return """
                [
                  {
                    "type": "PREFERENCE",
                    "content": "%s",
                    "importance": "medium",
                    "should_persist": true,
                    "evidence_message_index": 0
                  }
                ]
                """.formatted(content);
    }
}
