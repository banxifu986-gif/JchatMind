package com.kama.jchatmind.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.event.ChatSessionDeletedEvent;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryFacadeServiceImplTest {

    @Test
    void shouldReturnExtractedWhenMemoryCandidateExtractionCompletes() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7")).thenReturn(1);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(userMessages(1));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                chatMessageFacadeService,
                loggedInUser(7L)
        );

        MemoryExtractionResult result = extractionService.service().extractMemoryCandidates("7", "session-1");

        assertThat(result).isEqualTo(MemoryExtractionResult.EXTRACTED);
    }

    @Test
    void shouldReturnSkippedWhenMemoryExtractionThrottleIsNotReached() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(1, 2);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(userMessages(1));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                chatMessageFacadeService,
                loggedInUser(7L)
        );
        extractionService.service().extractMemoryCandidates("7", "session-1");

        MemoryExtractionResult result = extractionService.service().extractMemoryCandidates("7", "session-1");

        assertThat(result).isEqualTo(MemoryExtractionResult.SKIPPED);
    }

    @Test
    void shouldUseActiveMemoryQueryForAgentContext() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        service.getConfirmedMemories("7");

        assertThat(mockingDetails(userMemoryMapper).getInvocations())
                .extracting(invocation -> invocation.getMethod().getName())
                .contains("selectActiveByUserId")
                .doesNotContain("selectByUserId");
    }

    @Test
    void shouldRejectPastOrMissingExpirationWhenUpdatingOwnedMemory() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByIdAndUserId("memory-1", "7"))
                .thenReturn(memory("memory-1", "7", "当前记忆"));
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        assertThatThrownBy(() -> updateMemoryExpiration(
                service,
                "memory-1",
                LocalDateTime.now().minusMinutes(1)
        ))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过期时间必须晚于当前时间");
        assertThatThrownBy(() -> updateMemoryExpiration(service, "memory-1", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过期时间不能为空");
    }

    @Test
    void shouldAllowOwnedMemoryExpirationToBeSet() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class, invocation -> {
            if ("updateExpiration".equals(invocation.getMethod().getName())) {
                return 1;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        when(userMemoryMapper.selectByIdAndUserId("memory-1", "7"))
                .thenReturn(memory("memory-1", "7", "当前记忆"));
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );
        LocalDateTime futureExpiration = LocalDateTime.now().plusDays(1);

        service.updateMemoryExpiration("memory-1", futureExpiration);

        assertThat(mockingDetails(userMemoryMapper).getInvocations()).anySatisfy(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("updateExpiration");
            assertThat(invocation.getArguments()).containsExactly("memory-1", "7", futureExpiration);
        });
    }

    @Test
    void shouldUseActiveMemoriesWhenVectorRecallIsUnavailable() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemory activeMemory = memory("memory-active", "7", "当前记忆");
        when(userMemoryMapper.selectActiveByUserId("7")).thenReturn(List.of(activeMemory));
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        List<UserMemory> memories = service.recallRelevantMemories("7", "用户偏好", 5);

        assertThat(memories).containsExactly(activeMemory);
        verify(userMemoryMapper).selectActiveByUserId("7");
        verify(userMemoryMapper, never()).selectByUserId("7");
    }

    @Test
    void shouldUseActiveMemoriesToSupplementIncompleteVectorRecall() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemory semanticMemory = memory("memory-semantic", "7", "匹配记忆");
        semanticMemory.setEmbedding(new float[]{1.0F, 0.0F});
        UserMemory nonEmbeddingMemory = memory("memory-fallback", "7", "无向量记忆");
        when(userMemoryMapper.similaritySearch("7", "[1.0,0.0]", 2))
                .thenReturn(List.of(semanticMemory));
        when(userMemoryMapper.selectActiveByUserId("7"))
                .thenReturn(List.of(semanticMemory, nonEmbeddingMemory));
        RagService ragService = mock(RagService.class);
        when(ragService.embed("用户偏好")).thenReturn(new float[]{1.0F, 0.0F});
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L),
                ragService
        );

        List<UserMemory> memories = service.recallRelevantMemories("7", "用户偏好", 2);

        assertThat(memories).containsExactly(semanticMemory, nonEmbeddingMemory);
        verify(userMemoryMapper).selectActiveByUserId("7");
        verify(userMemoryMapper, never()).selectByUserId("7");
    }

    @Test
    void shouldUseActiveMemoriesWhenVectorRecallFails() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemory activeMemory = memory("memory-active", "7", "当前记忆");
        when(userMemoryMapper.selectActiveByUserId("7")).thenReturn(List.of(activeMemory));
        RagService ragService = mock(RagService.class);
        when(ragService.embed("用户偏好")).thenThrow(new IllegalStateException("embedding unavailable"));
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L),
                ragService
        );

        List<UserMemory> memories = service.recallRelevantMemories("7", "用户偏好", 5);

        assertThat(memories).containsExactly(activeMemory);
        verify(userMemoryMapper).selectActiveByUserId("7");
        verify(userMemoryMapper, never()).selectByUserId("7");
    }

    @Test
    void shouldResetExtractionThrottleAfterSessionDeletion() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(1, 1);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(userMessages(1));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                chatMessageFacadeService,
                loggedInUser(7L)
        );

        extractionService.service().extractMemoryCandidates("7", "session-1");
        extractionService.service().onChatSessionDeleted(new ChatSessionDeletedEvent("session-1"));
        extractionService.service().extractMemoryCandidates("7", "session-1");

        verify(extractionService.requestSpec(), times(2)).user(anyString());
    }

    @Test
    void shouldReleaseExtractionStateWhenSessionMessageCountCannotBeRead() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(1)
                .thenThrow(new BizException("聊天会话不存在: session-1"))
                .thenReturn(1);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(userMessages(1));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                chatMessageFacadeService,
                loggedInUser(7L)
        );

        extractionService.service().extractMemoryCandidates("7", "session-1");
        assertThatThrownBy(() -> extractionService.service().extractMemoryCandidates("7", "session-1"))
                .isInstanceOf(BizException.class)
                .hasMessage("聊天会话不存在: session-1");
        extractionService.service().extractMemoryCandidates("7", "session-1");

        verify(extractionService.requestSpec(), times(2)).user(anyString());
    }

    @Test
    void shouldKeepPersistableExtractionAsPendingCandidateUntilUserConfirmation() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(List.of(ChatMessageDTO.builder()
                        .id("message-1")
                        .role(ChatMessageDTO.RoleType.USER)
                        .content("我喜欢简洁回答")
                        .build()));
        when(candidateMapper.insert(any(UserMemoryCandidate.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, UserMemoryCandidate.class).setId("candidate-1");
            return 1;
        });
        when(candidateMapper.updateStatusById(any(), any())).thenReturn(1);
        when(userMemoryMapper.insert(any(UserMemory.class))).thenReturn(1);

        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                chatMessageFacadeService,
                loggedInUser(7L)
        );

        service.extractMemoryCandidates("7", "session-1");

        ArgumentCaptor<UserMemoryCandidate> candidateCaptor = ArgumentCaptor.forClass(UserMemoryCandidate.class);
        verify(candidateMapper).insert(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getStatus()).isEqualTo(UserMemoryCandidate.STATUS_PENDING);
        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
        verify(candidateMapper, never()).updateStatusById(any(), any());
    }

    @Test
    void shouldUseSessionTotalInsteadOfRecentWindowForExtractionThreshold() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(8, 9, 10, 11);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(userMessages(8));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                chatMessageFacadeService,
                loggedInUser(7L)
        );

        extractionService.service().extractMemoryCandidates("7", "session-1");
        extractionService.service().extractMemoryCandidates("7", "session-1");
        extractionService.service().extractMemoryCandidates("7", "session-1");
        extractionService.service().extractMemoryCandidates("7", "session-1");

        verify(extractionService.requestSpec(), times(2)).user(anyString());
        verify(chatMessageFacadeService, times(2))
                .getChatMessagesBySessionIdRecently("session-1", 8, "7");
    }

    @Test
    void shouldExtractAgainWhenUserMessageCountDropsAfterHistoryDeletion() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(4, 2);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(userMessages(4), userMessages(2));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                chatMessageFacadeService,
                loggedInUser(7L)
        );

        extractionService.service().extractMemoryCandidates("7", "session-1");
        extractionService.service().extractMemoryCandidates("7", "session-1");

        verify(extractionService.requestSpec(), times(2)).user(anyString());
    }

    @Test
    void shouldRetrySameUserMessageCountWhenCandidatePersistenceFails() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.insert(any(UserMemoryCandidate.class)))
                .thenThrow(new BizException("candidate persistence failed"))
                .thenReturn(1);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(1);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(userMessages(1));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                candidateMapper,
                chatMessageFacadeService,
                loggedInUser(7L)
        );
        when(extractionService.callResponseSpec().content()).thenReturn("""
                [{"type":"PREFERENCE","content":"偏好简洁回答","importance":"medium","should_persist":true,"evidence_message_index":0}]
                """);

        assertThatThrownBy(() -> extractionService.service().extractMemoryCandidates("7", "session-1"))
                .isInstanceOf(BizException.class)
                .hasMessage("candidate persistence failed");

        extractionService.service().extractMemoryCandidates("7", "session-1");

        verify(extractionService.requestSpec(), times(2)).user(anyString());
        verify(candidateMapper, times(2)).insert(any(UserMemoryCandidate.class));
    }

    @Test
    void shouldFallBackToKeywordExtractionWhenLlmResponseIsInvalid() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.insert(any(UserMemoryCandidate.class))).thenReturn(1);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(1);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(List.of(ChatMessageDTO.builder()
                        .id("message-1")
                        .role(ChatMessageDTO.RoleType.USER)
                        .content("请记住我喜欢简洁回答")
                        .build()));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                candidateMapper,
                chatMessageFacadeService,
                loggedInUser(7L)
        );
        when(extractionService.callResponseSpec().content()).thenReturn("[invalid");

        assertThat(extractionService.service().extractMemoryCandidates("7", "session-1"))
                .isEqualTo(MemoryExtractionResult.EXTRACTED);

        ArgumentCaptor<UserMemoryCandidate> candidateCaptor = ArgumentCaptor.forClass(UserMemoryCandidate.class);
        verify(candidateMapper).insert(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getContent()).isEqualTo("请记住我喜欢简洁回答");
    }

    @Test
    void shouldFallBackToKeywordExtractionWhenLlmResponseIsBlank() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.insert(any(UserMemoryCandidate.class))).thenReturn(1);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(1);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(List.of(ChatMessageDTO.builder()
                        .id("message-1")
                        .role(ChatMessageDTO.RoleType.USER)
                        .content("请记住我喜欢简洁回答")
                        .build()));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                candidateMapper,
                chatMessageFacadeService,
                loggedInUser(7L)
        );
        when(extractionService.callResponseSpec().content()).thenReturn("  ");

        assertThat(extractionService.service().extractMemoryCandidates("7", "session-1"))
                .isEqualTo(MemoryExtractionResult.EXTRACTED);

        ArgumentCaptor<UserMemoryCandidate> candidateCaptor = ArgumentCaptor.forClass(UserMemoryCandidate.class);
        verify(candidateMapper).insert(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getContent()).isEqualTo("请记住我喜欢简洁回答");
    }

    @Test
    void shouldNotLogInvalidLlmResponseWhenKeywordFallbackSucceeds() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.insert(any(UserMemoryCandidate.class))).thenReturn(1);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(1);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(List.of(ChatMessageDTO.builder()
                        .id("message-1")
                        .role(ChatMessageDTO.RoleType.USER)
                        .content("请记住我喜欢简洁回答")
                        .build()));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                candidateMapper,
                chatMessageFacadeService,
                loggedInUser(7L)
        );
        when(extractionService.callResponseSpec().content()).thenReturn("[UNIQUE_MODEL_RESPONSE_MARKER]");
        Logger logger = (Logger) LoggerFactory.getLogger(UserMemoryFacadeServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            extractionService.service().extractMemoryCandidates("7", "session-1");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("UNIQUE_MODEL_RESPONSE_MARKER");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void shouldAllowOnlyOneConcurrentExtractionForSameSession() throws Exception {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("7")).thenReturn(List.of());
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenReturn(1);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("session-1", 8, "7"))
                .thenReturn(userMessages(1));
        ExtractionService extractionService = createExtractionService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                chatMessageFacadeService,
                loggedInUser(7L)
        );
        CountDownLatch firstModelEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstModel = new CountDownLatch(1);
        CountDownLatch secondInvocationStarted = new CountDownLatch(1);
        CountDownLatch secondCountEntered = new CountDownLatch(1);
        AtomicInteger countCalls = new AtomicInteger();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        when(chatMessageFacadeService.countUserMessagesBySessionId("session-1", "7"))
                .thenAnswer(invocation -> {
                    if (countCalls.incrementAndGet() > 1) {
                        secondCountEntered.countDown();
                    }
                    return 1;
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            firstModelEntered.countDown();
            assertThat(releaseFirstModel.await(5, TimeUnit.SECONDS)).isTrue();
            return extractionService.requestSpec();
        }).when(extractionService.requestSpec()).user(anyString());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> {
                extractionService.service().extractMemoryCandidates("7", "session-1");
                return null;
            });
            assertThat(firstModelEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                secondInvocationStarted.countDown();
                extractionService.service().extractMemoryCandidates("7", "session-1");
                return null;
            });
            assertThat(secondInvocationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitThreadState(secondThread.get(), Thread.State.BLOCKED, 5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondCountEntered.getCount()).isEqualTo(1);
            releaseFirstModel.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        verify(extractionService.requestSpec(), times(1)).user(anyString());
    }

    @Test
    void shouldReturnOnlyPendingCandidatesForCurrentUser() {
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.selectByUserId("7")).thenReturn(List.of(
                candidate("candidate-pending", "7", UserMemoryCandidate.STATUS_PENDING),
                candidate("candidate-persisted", "7", UserMemoryCandidate.STATUS_PERSISTED),
                candidate("candidate-discarded", "7", UserMemoryCandidate.STATUS_DISCARDED)
        ));
        UserMemoryFacadeServiceImpl service = createService(
                mock(UserMemoryMapper.class),
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        var response = service.getUserMemoryCandidates();

        assertThat(response.getCandidates())
                .extracting(candidate -> candidate.getId())
                .containsExactly("candidate-pending");
    }

    @Test
    void shouldPersistOwnedPendingCandidateOnlyWhenConfirmed() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsPending();
        UserMemoryCandidate candidate = candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING);
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7")).thenReturn(candidate);
        when(userMemoryMapper.insert(any(UserMemory.class))).thenReturn(1);
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        LocalDateTime beforeConfirmation = LocalDateTime.now();
        confirmCandidate(service, "candidate-1");
        LocalDateTime afterConfirmation = LocalDateTime.now();

        ArgumentCaptor<UserMemory> memoryCaptor = ArgumentCaptor.forClass(UserMemory.class);
        verify(userMemoryMapper).insert(memoryCaptor.capture());
        UserMemory insertedMemory = memoryCaptor.getValue();
        assertThat(insertedMemory.getUserId()).isEqualTo("7");
        assertThat(insertedMemory.getContent()).isEqualTo(candidate.getContent());
        assertThat(insertedMemory.getSessionId()).isEqualTo(candidate.getSessionId());
        assertThat(insertedMemory.getExpiresAt())
                .isAfterOrEqualTo(beforeConfirmation.plusDays(365))
                .isBeforeOrEqualTo(afterConfirmation.plusDays(365));
        assertThat(mockingDetails(candidateMapper).getInvocations()).anySatisfy(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("markPersistedIfPending");
            assertThat(invocation.getArguments()).containsExactly("candidate-1", "7");
        });
    }

    @Test
    void shouldPersistConflictRelationshipInsteadOfPhysicallyDeletingPreviousMemory() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class, invocation -> {
            if ("markSupersededById".equals(invocation.getMethod().getName())) {
                return 1;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsPending();
        UserMemoryCandidate candidate = candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING);
        candidate.setContent("更新：我喜欢先给结论的简洁回答");
        UserMemory previousMemory = memory("memory-previous", "7", "我喜欢简洁回答");
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7")).thenReturn(candidate);
        when(userMemoryMapper.selectActiveByUserId("7")).thenReturn(List.of(previousMemory));
        when(userMemoryMapper.insert(any(UserMemory.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, UserMemory.class).setId("memory-current");
            return 1;
        });
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        LocalDateTime beforeConfirmation = LocalDateTime.now();
        confirmCandidate(service, "candidate-1");
        LocalDateTime afterConfirmation = LocalDateTime.now();

        verify(userMemoryMapper, never()).deleteById("memory-previous");
        ArgumentCaptor<UserMemory> memoryCaptor = ArgumentCaptor.forClass(UserMemory.class);
        verify(userMemoryMapper).insert(memoryCaptor.capture());
        assertThat(memoryCaptor.getValue().getExpiresAt())
                .isAfterOrEqualTo(beforeConfirmation.plusDays(365))
                .isBeforeOrEqualTo(afterConfirmation.plusDays(365));
        assertThat(mockingDetails(userMemoryMapper).getInvocations()).anySatisfy(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("markSupersededById");
            assertThat(invocation.getArguments()).containsExactly("memory-previous", "memory-current");
        });
    }

    @Test
    void shouldRejectConflictConfirmationWhenPreviousMemoryCannotBeMarkedSuperseded() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class, invocation -> {
            if ("markSupersededById".equals(invocation.getMethod().getName())) {
                return 0;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsPending();
        UserMemoryCandidate candidate = candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING);
        candidate.setContent("更新：我喜欢先给结论的简洁回答");
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7")).thenReturn(candidate);
        when(userMemoryMapper.selectActiveByUserId("7"))
                .thenReturn(List.of(memory("memory-previous", "7", "我喜欢简洁回答")));
        when(userMemoryMapper.insert(any(UserMemory.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, UserMemory.class).setId("memory-current");
            return 1;
        });
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        assertThatThrownBy(() -> confirmCandidate(service, "candidate-1"))
                .isInstanceOf(BizException.class)
                .hasMessage("保存记忆冲突关系失败");

        verify(userMemoryMapper, never()).deleteById("memory-previous");
    }

    @Test
    void shouldNotInsertSameTypeSemanticDuplicateWhenConfirmingCandidate() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsPending();
        UserMemoryCandidate candidate = candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING);
        UserMemory existingMemory = memory("memory-1", "7", "已有偏好");
        existingMemory.setEmbedding(new float[]{1.0F, 0.0F});
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7")).thenReturn(candidate);
        when(userMemoryMapper.selectActiveByUserId("7")).thenReturn(List.of(existingMemory));
        RagService ragService = mock(RagService.class);
        when(ragService.embed(candidate.getContent())).thenReturn(new float[]{1.0F, 0.0F});
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L),
                ragService
        );

        assertThatCode(() -> confirmCandidate(service, "candidate-1")).doesNotThrowAnyException();

        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
        verify(userMemoryMapper).selectActiveByUserId("7");
        verify(ragService).embed(candidate.getContent());
        assertThat(mockingDetails(candidateMapper).getInvocations()).anySatisfy(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("markPersistedIfPending");
            assertThat(invocation.getArguments()).containsExactly("candidate-1", "7");
        });
    }

    @Test
    void shouldNotInsertFiniteLargeMagnitudeSemanticDuplicateWhenConfirmingCandidate() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsPending();
        UserMemoryCandidate candidate = candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING);
        float[] embedding = new float[]{Float.MAX_VALUE, 0.0F};
        UserMemory existingMemory = memory("memory-1", "7", "已有偏好");
        existingMemory.setEmbedding(embedding);
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7")).thenReturn(candidate);
        when(userMemoryMapper.selectActiveByUserId("7")).thenReturn(List.of(existingMemory));
        RagService ragService = mock(RagService.class);
        when(ragService.embed(candidate.getContent())).thenReturn(embedding);
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L),
                ragService
        );

        assertThatCode(() -> confirmCandidate(service, "candidate-1")).doesNotThrowAnyException();

        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
    }

    @Test
    void shouldInsertCandidateWhenNearestSemanticMemoryHasDifferentType() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.insert(any(UserMemory.class))).thenReturn(1);
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsPending();
        UserMemoryCandidate candidate = candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING);
        UserMemory existingMemory = memory("memory-1", "7", "已有事实");
        existingMemory.setMemoryType("FACT");
        existingMemory.setEmbedding(new float[]{1.0F, 0.0F});
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7")).thenReturn(candidate);
        when(userMemoryMapper.selectActiveByUserId("7")).thenReturn(List.of(existingMemory));
        RagService ragService = mock(RagService.class);
        when(ragService.embed(candidate.getContent())).thenReturn(new float[]{1.0F, 0.0F});
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L),
                ragService
        );

        confirmCandidate(service, "candidate-1");

        ArgumentCaptor<UserMemory> memoryCaptor = ArgumentCaptor.forClass(UserMemory.class);
        verify(userMemoryMapper).insert(memoryCaptor.capture());
        assertThat(memoryCaptor.getValue().getEmbedding()).containsExactly(1.0F, 0.0F);
        verify(ragService, times(1)).embed(candidate.getContent());
    }

    @Test
    void shouldInsertCandidateOutsideSemanticDuplicateThreshold() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.insert(any(UserMemory.class))).thenReturn(1);
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsPending();
        UserMemoryCandidate candidate = candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING);
        UserMemory existingMemory = memory("memory-1", "7", "不同偏好");
        existingMemory.setEmbedding(new float[]{0.0F, 1.0F});
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7")).thenReturn(candidate);
        when(userMemoryMapper.selectActiveByUserId("7")).thenReturn(List.of(existingMemory));
        RagService ragService = mock(RagService.class);
        when(ragService.embed(candidate.getContent())).thenReturn(new float[]{1.0F, 0.0F});
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L),
                ragService
        );

        confirmCandidate(service, "candidate-1");

        verify(userMemoryMapper).insert(any(UserMemory.class));
    }

    @Test
    void shouldConfirmCandidateWhenSemanticDuplicateEmbeddingIsUnavailable() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.insert(any(UserMemory.class))).thenReturn(1);
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsPending();
        UserMemoryCandidate candidate = candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING);
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7")).thenReturn(candidate);
        RagService ragService = mock(RagService.class);
        when(ragService.embed(candidate.getContent())).thenThrow(new IllegalStateException("embedding unavailable"));
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L),
                ragService
        );

        confirmCandidate(service, "candidate-1");

        verify(userMemoryMapper).insert(any(UserMemory.class));
        verify(userMemoryMapper, never()).selectActiveByUserId("7");
    }

    @Test
    void shouldConfirmCandidateWhenExistingSemanticVectorIsInvalid() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.insert(any(UserMemory.class))).thenReturn(1);
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsPending();
        UserMemoryCandidate candidate = candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING);
        UserMemory existingMemory = memory("memory-1", "7", "已有偏好");
        existingMemory.setEmbedding(new float[]{Float.NaN, 0.0F});
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7")).thenReturn(candidate);
        when(userMemoryMapper.selectActiveByUserId("7")).thenReturn(List.of(existingMemory));
        RagService ragService = mock(RagService.class);
        when(ragService.embed(candidate.getContent())).thenReturn(new float[]{1.0F, 0.0F});
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L),
                ragService
        );

        confirmCandidate(service, "candidate-1");

        verify(userMemoryMapper).insert(any(UserMemory.class));
    }

    @Test
    void shouldRejectConfirmationForMissingOrForeignCandidate() {
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.selectByIdAndUserId("candidate-foreign", "7")).thenReturn(null);
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        assertThatThrownBy(() -> confirmCandidate(service, "candidate-foreign"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("候选记忆不存在");
        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
    }

    @Test
    void shouldRejectConfirmationForNonPendingCandidate() {
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7"))
                .thenReturn(candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PERSISTED));
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        assertThatThrownBy(() -> confirmCandidate(service, "candidate-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("候选记忆状态不可确认");
        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
    }

    @Test
    void shouldDiscardOwnedPendingCandidateWithoutPersistingMemory() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryCandidateMapper candidateMapper = candidateMapperThatClaimsDiscardedPending();
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7"))
                .thenReturn(candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PENDING));
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        discardCandidate(service, "candidate-1");

        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
        assertThat(mockingDetails(candidateMapper).getInvocations()).anySatisfy(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("markDiscardedIfPending");
            assertThat(invocation.getArguments()).containsExactly("candidate-1", "7");
        });
    }

    @Test
    void shouldRejectDiscardForMissingOrForeignCandidate() {
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.selectByIdAndUserId("candidate-foreign", "7")).thenReturn(null);
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        assertThatThrownBy(() -> discardCandidate(service, "candidate-foreign"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("候选记忆不存在");
        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
    }

    @Test
    void shouldRejectDiscardForNonPendingCandidate() {
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        when(candidateMapper.selectByIdAndUserId("candidate-1", "7"))
                .thenReturn(candidate("candidate-1", "7", UserMemoryCandidate.STATUS_PERSISTED));
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        assertThatThrownBy(() -> discardCandidate(service, "candidate-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("候选记忆状态不可忽略");
        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
    }

    @Test
    void shouldClearOnlyCurrentUsersConfirmedMemoriesWithoutTouchingCandidates() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                candidateMapper,
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        clearMemories(service);

        assertThat(mockingDetails(userMemoryMapper).getInvocations()).anySatisfy(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("deleteByUserId");
            assertThat(invocation.getArguments()).containsExactly("7");
        });
        assertThat(mockingDetails(candidateMapper).getInvocations()).isEmpty();
    }

    @Test
    void shouldUpdateOnlyCurrentUsersMemoryContentEmbeddingAndExpiration() {
        UserMemoryMapper userMemoryMapper = userMemoryMapperThatUpdatesContent();
        when(userMemoryMapper.selectByIdAndUserId("memory-1", "7"))
                .thenReturn(memory("memory-1", "7", "旧记忆"));
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        LocalDateTime beforeUpdate = LocalDateTime.now();
        updateMemory(service, "memory-1", "  新记忆  ");
        LocalDateTime afterUpdate = LocalDateTime.now();

        assertThat(mockingDetails(userMemoryMapper).getInvocations()).anySatisfy(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("updateContentEmbeddingAndExpiration");
            assertThat(invocation.getArguments())
                    .contains("memory-1", "7", "新记忆", null);
            assertThat((LocalDateTime) invocation.getArguments()[4])
                    .isAfterOrEqualTo(beforeUpdate.plusDays(365))
                    .isBeforeOrEqualTo(afterUpdate.plusDays(365));
        });
    }

    @Test
    void shouldRejectBlankOrForeignMemoryEdits() {
        UserMemoryMapper userMemoryMapper = userMemoryMapperThatUpdatesContent();
        when(userMemoryMapper.selectByIdAndUserId("memory-foreign", "7")).thenReturn(null);
        UserMemoryFacadeServiceImpl service = createService(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                loggedInUser(7L)
        );

        assertThatThrownBy(() -> updateMemory(service, "memory-1", "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户记忆内容不能为空");
        assertThatThrownBy(() -> updateMemory(service, "memory-foreign", "新记忆"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户记忆不存在");
        assertThat(mockingDetails(userMemoryMapper).getInvocations())
                .noneMatch(invocation -> "updateContentAndEmbedding".equals(invocation.getMethod().getName())
                        || "updateContentEmbeddingAndExpiration".equals(invocation.getMethod().getName()));
    }

    private UserMemoryFacadeServiceImpl createService(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper candidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            RequestScopeData requestScopeData
    ) {
        return new UserMemoryFacadeServiceImpl(
                userMemoryMapper,
                candidateMapper,
                chatMessageFacadeService,
                requestScopeData
        );
    }

    private UserMemoryFacadeServiceImpl createService(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper candidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            RequestScopeData requestScopeData,
            RagService ragService
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<RagService> ragServiceProvider = mock(ObjectProvider.class);
        when(ragServiceProvider.getIfAvailable()).thenReturn(ragService);
        return new UserMemoryFacadeServiceImpl(
                userMemoryMapper,
                candidateMapper,
                chatMessageFacadeService,
                requestScopeData,
                null,
                ragServiceProvider
        );
    }

    private ExtractionService createExtractionService(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper candidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            RequestScopeData requestScopeData
    ) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("[]");

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClientRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(new ChatClientRegistry(Map.of("deepseek-chat", chatClient)));
        UserMemoryFacadeServiceImpl service = new UserMemoryFacadeServiceImpl(
                userMemoryMapper,
                candidateMapper,
                chatMessageFacadeService,
                requestScopeData,
                registryProvider,
                null
        );
        return new ExtractionService(service, requestSpec, callResponseSpec);
    }

    private List<ChatMessageDTO> userMessages(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> ChatMessageDTO.builder()
                        .id("message-" + index)
                        .role(ChatMessageDTO.RoleType.USER)
                        .content("用户信息-" + index)
                        .build())
                .toList();
    }

    private boolean awaitThreadState(Thread thread, Thread.State expectedState, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == expectedState) {
                return true;
            }
            Thread.yield();
        }
        return false;
    }

    private RequestScopeData loggedInUser(Long userId) {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(userId);
        requestScopeData.setLogin(true);
        return requestScopeData;
    }

    private UserMemoryCandidate candidate(String id, String userId, String status) {
        return UserMemoryCandidate.builder()
                .id(id)
                .userId(userId)
                .sessionId("session-1")
                .memoryType("PREFERENCE")
                .content("我喜欢简洁回答")
                .evidence("我喜欢简洁回答")
                .importance("medium")
                .status(status)
                .build();
    }

    private UserMemory memory(String id, String userId, String content) {
        return UserMemory.builder()
                .id(id)
                .userId(userId)
                .sessionId("session-1")
                .memoryType("PREFERENCE")
                .content(content)
                .importance("medium")
                .build();
    }

    private UserMemoryMapper userMemoryMapperThatUpdatesContent() {
        return mock(UserMemoryMapper.class, invocation -> {
            if ("updateContentAndEmbedding".equals(invocation.getMethod().getName())
                    || "updateContentEmbeddingAndExpiration".equals(invocation.getMethod().getName())) {
                return 1;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private UserMemoryCandidateMapper candidateMapperThatClaimsPending() {
        return mock(UserMemoryCandidateMapper.class, invocation -> {
            if ("markPersistedIfPending".equals(invocation.getMethod().getName())) {
                return 1;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private UserMemoryCandidateMapper candidateMapperThatClaimsDiscardedPending() {
        return mock(UserMemoryCandidateMapper.class, invocation -> {
            if ("markDiscardedIfPending".equals(invocation.getMethod().getName())) {
                return 1;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private void confirmCandidate(UserMemoryFacadeServiceImpl service, String candidateId) {
        try {
            Method method = UserMemoryFacadeServiceImpl.class.getMethod("confirmUserMemoryCandidate", String.class);
            method.invoke(service, candidateId);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(e.getCause());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("候选记忆确认服务尚未实现", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void discardCandidate(UserMemoryFacadeServiceImpl service, String candidateId) {
        try {
            Method method = UserMemoryFacadeServiceImpl.class.getMethod("discardUserMemoryCandidate", String.class);
            method.invoke(service, candidateId);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(e.getCause());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("候选记忆忽略服务尚未实现", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void clearMemories(UserMemoryFacadeServiceImpl service) {
        try {
            Method method = UserMemoryFacadeServiceImpl.class.getMethod("clearUserMemories");
            method.invoke(service);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(e.getCause());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("清空用户长期记忆服务尚未实现", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void updateMemory(UserMemoryFacadeServiceImpl service, String memoryId, String content) {
        try {
            Method method = UserMemoryFacadeServiceImpl.class.getMethod("updateMemory", String.class, String.class);
            method.invoke(service, memoryId, content);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(e.getCause());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("用户长期记忆编辑服务尚未实现", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void updateMemoryExpiration(
            UserMemoryFacadeServiceImpl service,
            String memoryId,
            LocalDateTime expiresAt
    ) {
        try {
            Method method = UserMemoryFacadeServiceImpl.class.getMethod(
                    "updateMemoryExpiration",
                    String.class,
                    LocalDateTime.class
            );
            method.invoke(service, memoryId, expiresAt);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(e.getCause());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("用户记忆过期时间更新服务尚未实现", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record ExtractionService(
            UserMemoryFacadeServiceImpl service,
            ChatClient.ChatClientRequestSpec requestSpec,
            ChatClient.CallResponseSpec callResponseSpec
    ) {
    }
}
