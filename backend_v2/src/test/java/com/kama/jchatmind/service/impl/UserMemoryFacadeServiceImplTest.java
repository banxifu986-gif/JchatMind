package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryFacadeServiceImplTest {

    @Test
    void shouldPropagateWhenConfirmedMemoriesQueryFails() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("user-1"))
                .thenThrow(new RuntimeException("db unavailable"));

        UserMemoryFacadeServiceImpl service = new UserMemoryFacadeServiceImpl(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                null,
                null
        );

        assertThrows(RuntimeException.class, () -> service.getConfirmedMemories("user-1"));
    }

    @Test
    void shouldNotInsertNewMemoryWhenAutomaticConflictLookupFails() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        UserMemoryCandidateMapper candidateMapper = mock(UserMemoryCandidateMapper.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);

        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently("user-1", "session-1", 8))
                .thenReturn(List.of(userMessage("msg-1", "我喜欢手冲咖啡")));
        when(userMemoryMapper.selectByUserId("user-1"))
                .thenReturn(List.of())
                .thenThrow(new RuntimeException("db unavailable"));
        when(userMemoryMapper.selectByUserIdAndContent(eq("user-1"), any())).thenReturn(null);
        when(candidateMapper.selectByUserIdAndContent(eq("user-1"), any())).thenReturn(null);
        when(candidateMapper.insert(any(UserMemoryCandidate.class))).thenAnswer(invocation -> {
            UserMemoryCandidate candidate = invocation.getArgument(0);
            candidate.setId("candidate-1");
            return 1;
        });

        UserMemoryFacadeServiceImpl service = new UserMemoryFacadeServiceImpl(
                userMemoryMapper,
                candidateMapper,
                chatMessageFacadeService,
                fixedProvider(chatClientRegistry("""
                        [
                          {
                            "type": "PREFERENCE",
                            "content": "%s喜欢手冲咖啡",
                            "importance": "high",
                            "should_persist": true,
                            "evidence_message_index": 0
                          }
                        ]
                        """.formatted(memoryUpdatePrefix()))),
                null
        );

        assertThrows(RuntimeException.class, () -> service.extractMemoryCandidates("user-1", "session-1"));
        verify(userMemoryMapper, never()).insert(any(UserMemory.class));
    }

    @Test
    void shouldFallbackToRecentMemoriesWhenSemanticRecallFails() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        List<UserMemory> recentMemories = List.of(
                UserMemory.builder().id("m1").content("memory-1").build(),
                UserMemory.builder().id("m2").content("memory-2").build()
        );
        when(userMemoryMapper.selectByUserId("user-1")).thenReturn(recentMemories);

        RagService ragService = mock(RagService.class);
        when(ragService.embed("hello"))
                .thenThrow(new RuntimeException("ollama unavailable"));

        UserMemoryFacadeServiceImpl service = new UserMemoryFacadeServiceImpl(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                null,
                fixedProvider(ragService)
        );

        assertEquals(recentMemories, service.recallRelevantMemories("user-1", "hello", 5));
    }

    @Test
    void shouldReturnEmptyWhenAllRecallFallbackPathsFail() {
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        when(userMemoryMapper.selectByUserId("user-1"))
                .thenThrow(new RuntimeException("db unavailable"));

        RagService ragService = mock(RagService.class);
        when(ragService.embed("hello"))
                .thenThrow(new RuntimeException("ollama unavailable"));

        UserMemoryFacadeServiceImpl service = new UserMemoryFacadeServiceImpl(
                userMemoryMapper,
                mock(UserMemoryCandidateMapper.class),
                mock(ChatMessageFacadeService.class),
                null,
                fixedProvider(ragService)
        );

        assertEquals(List.of(), service.recallRelevantMemories("user-1", "hello", 5));
    }

    private static <T> ObjectProvider<T> fixedProvider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    private static String memoryUpdatePrefix() {
        try {
            Field field = UserMemoryFacadeServiceImpl.class.getDeclaredField("MEMORY_UPDATE_PREFIX");
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatClientRegistry chatClientRegistry(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(content);
        return new ChatClientRegistry(Map.of("deepseek-chat", chatClient));
    }

    private static ChatMessageDTO userMessage(String id, String content) {
        return ChatMessageDTO.builder()
                .id(id)
                .content(content)
                .role(ChatMessageDTO.RoleType.USER)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
