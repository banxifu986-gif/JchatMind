package com.kama.jchatmind.memory;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LongMemEvalMemorySchemaContractTest {

    private static final Path SCHEMA_PATH = Path.of(
            "..", "longmemeval-eval", "init", "001-memory-evaluation-schema.sql"
    );
    private static final Path USER_MEMORY_MAPPER_PATH = Path.of(
            "src", "main", "resources", "mapper", "UserMemoryMapper.xml"
    );
    private static final Path USER_MEMORY_CANDIDATE_MAPPER_PATH = Path.of(
            "src", "main", "resources", "mapper", "UserMemoryCandidateMapper.xml"
    );
    private static final Path CHAT_SESSION_MAPPER_PATH = Path.of(
            "src", "main", "resources", "mapper", "ChatSessionMapper.xml"
    );
    private static final Path CHAT_MESSAGE_MAPPER_PATH = Path.of(
            "src", "main", "resources", "mapper", "ChatMessageMapper.xml"
    );

    @Test
    void provisionsOnlyTheIsolatedTablesNeededByProductionMemoryMappers() throws Exception {
        assertThat(Files.exists(SCHEMA_PATH)).isTrue();
        String schema = Files.readString(SCHEMA_PATH).toLowerCase();
        String userMemoryMapper = Files.readString(USER_MEMORY_MAPPER_PATH).toLowerCase();
        String candidateMapper = Files.readString(USER_MEMORY_CANDIDATE_MAPPER_PATH).toLowerCase();

        assertThat(schema)
                .contains("create table if not exists public.jchatmind_user")
                .contains("create table if not exists public.user_memory")
                .contains("create table if not exists public.user_memory_candidate")
                .contains("embedding vector(1024)")
                .contains("superseded_by_memory_id uuid")
                .contains("expires_at timestamp")
                .contains("status varchar(16)")
                .contains("create extension if not exists vector")
                .contains("longmemeval_eval");

        String userMemoryTable = tableDefinition(schema, "user_memory");
        String candidateTable = tableDefinition(schema, "user_memory_candidate");
        assertThat(userMemoryTable).contains("user_id varchar(64) not null")
                .doesNotContain("references public.jchatmind_user(user_id)");
        assertThat(candidateTable).contains("user_id varchar(64) not null")
                .doesNotContain("references public.jchatmind_user(user_id)");

        for (String field : new String[]{
                "id", "user_id", "session_id", "memory_type", "content", "importance",
                "evidence_message_id", "evidence_text", "superseded_by_memory_id", "expires_at",
                "embedding", "created_at", "updated_at"
        }) {
            assertThat(schema).contains(field);
            assertThat(userMemoryMapper).contains(field);
        }
        for (String field : new String[]{
                "id", "user_id", "session_id", "memory_type", "content", "evidence",
                "importance", "evidence_message_id", "status", "created_at", "updated_at"
        }) {
            assertThat(schema).contains(field);
            assertThat(candidateMapper).contains(field);
        }
        assertThat(schema).contains("foreign key (superseded_by_memory_id)")
                .contains("on delete cascade")
                .contains("status in ('pending', 'persisted', 'discarded')");
    }

    @Test
    void provisionsProductionChatTablesNeededToReadReplayHistory() throws Exception {
        String schema = Files.readString(SCHEMA_PATH).toLowerCase();
        String chatSessionMapper = Files.readString(CHAT_SESSION_MAPPER_PATH).toLowerCase();
        String chatMessageMapper = Files.readString(CHAT_MESSAGE_MAPPER_PATH).toLowerCase();
        String chatSessionTable = tableDefinition(schema, "chat_session");
        String chatMessageTable = tableDefinition(schema, "chat_message");

        for (String field : new String[]{
                "id", "user_id", "agent_id", "title", "metadata", "created_at", "updated_at"
        }) {
            assertThat(chatSessionTable).contains(field);
            assertThat(chatSessionMapper).contains(field);
        }
        for (String field : new String[]{
                "id", "session_id", "role", "content", "metadata", "created_at", "updated_at"
        }) {
            assertThat(chatMessageTable).contains(field);
            assertThat(chatMessageMapper).contains(field);
        }
        assertThat(chatSessionTable).contains("user_id text not null");
        assertThat(chatSessionMapper).contains("user_id = cast(#{userid} as text)");
        assertThat(chatMessageTable)
                .contains("session_id uuid not null")
                .contains("references public.chat_session(id)")
                .contains("on delete cascade");
        assertThat(schema)
                .contains("idx_longmemeval_chat_session_user_id")
                .contains("idx_longmemeval_chat_message_session_created");
    }

    private String tableDefinition(String schema, String tableName) {
        int start = schema.indexOf("create table if not exists public." + tableName);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = schema.indexOf("\n);", start);
        assertThat(end).isGreaterThan(start);
        return schema.substring(start, end);
    }
}
