package com.kama.jchatmind.model.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class ChunkBgeM3Test {

    @Test
    void shouldProvideNoArgsConstructorForMyBatisFieldMapping() {
        assertThatCode(() -> ChunkBgeM3.class.getDeclaredConstructor().newInstance())
                .doesNotThrowAnyException();
    }

    @Test
    void shouldMapBm25ProjectionFieldsWhenReadingChunks() throws Exception {
        String mapper = Files.readString(Path.of("src", "main", "resources", "mapper", "ChunkBgeM3Mapper.xml"));

        assertThat(mapper)
                .contains("<result property=\"titleBm25Vector\" column=\"title_bm25_vector\"")
                .contains("<result property=\"contentBm25Vector\" column=\"content_bm25_vector\"")
                .contains("<result property=\"bm25IndexVersion\" column=\"bm25_index_version\"")
                .contains("title_bm25_vector")
                .contains("content_bm25_vector")
                .contains("bm25_index_version");
    }
}
