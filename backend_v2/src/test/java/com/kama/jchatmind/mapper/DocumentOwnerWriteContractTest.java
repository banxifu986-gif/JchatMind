package com.kama.jchatmind.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentOwnerWriteContractTest {

    @Test
    void shouldBindDocumentUpdateToKnowledgeBaseOwnerInMapperSql() throws IOException {
        String mapperXml = Files.readString(Path.of("src/main/resources/mapper/DocumentMapper.xml"));

        assertThat(mapperXml).containsPattern(
                "(?s)<update id=\"updateByIdAndOwnerId\".*?WHERE id = CAST\\(#\\{document.id} AS uuid\\).*?"
                        + "AND EXISTS \\(.*?FROM knowledge_base.*?knowledge_base.id = document.kb_id.*?"
                        + "knowledge_base.owner_id = CAST\\(#\\{ownerId} AS bigint\\).*?\\).*?</update>"
        );
    }
}
