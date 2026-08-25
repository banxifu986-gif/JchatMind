package com.kama.jchatmind.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryExpirationContractTest {

    @Test
    void shouldPersistExpirationAndExcludeExpiredMemoriesFromAgentReads() throws IOException {
        String mapperXml = Files.readString(Path.of("src/main/resources/mapper/UserMemoryMapper.xml"));
        Path migrationPath = Path.of("../sql/user-memory/2026-08-25-add-user-memory-expires-at.sql");

        assertThat(Files.exists(migrationPath)).isTrue();
        String migration = Files.readString(migrationPath);
        assertThat(migration).contains("ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP");
        assertThat(mapperXml).contains("<result property=\"expiresAt\" column=\"expires_at\" jdbcType=\"TIMESTAMP\"/>");
        assertThat(mapperXml).contains("id=\"selectActiveByUserId\"");
        assertThat(mapperXml).contains("(expires_at IS NULL OR expires_at &gt; NOW())");
        assertThat(mapperXml).containsPattern(
                "(?s)<select id=\"selectByIdAndUserId\".*?AND user_id = #\\{userId}.*?AND superseded_by_memory_id IS NULL.*?</select>"
        );
        assertThat(mapperXml).containsPattern(
                "(?s)<update id=\"updateExpiration\".*?AND user_id = #\\{userId}.*?AND superseded_by_memory_id IS NULL.*?</update>"
        );
        assertThat(mapperXml).containsPattern(
                "(?s)<update id=\"updateContentEmbeddingAndExpiration\".*?expires_at = #\\{expiresAt,jdbcType=TIMESTAMP}.*?AND user_id = #\\{userId}.*?AND superseded_by_memory_id IS NULL.*?</update>"
        );
    }
}
