package com.kama.jchatmind.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseDeletionContractTest {

    @Test
    void shouldBindKnowledgeBaseDeleteToOwnerInMapperSql() throws IOException {
        String mapperXml = Files.readString(Path.of("src/main/resources/mapper/KnowledgeBaseMapper.xml"));

        assertThat(mapperXml).containsPattern(
                "(?s)<delete id=\"deleteByIdAndOwnerId\".*?WHERE id = CAST\\(#\\{id} AS uuid\\).*?"
                        + "AND owner_id = CAST\\(#\\{ownerId} AS bigint\\).*?</delete>"
        );
    }

    @Test
    void shouldBindKnowledgeBaseUpdateToOwnerInMapperSql() throws IOException {
        String mapperXml = Files.readString(Path.of("src/main/resources/mapper/KnowledgeBaseMapper.xml"));

        assertThat(mapperXml).containsPattern(
                "(?s)<update id=\"updateById\".*?WHERE id = CAST\\(#\\{id} AS uuid\\).*?"
                        + "AND owner_id = CAST\\(#\\{ownerId} AS bigint\\).*?</update>"
        );
    }
}
