package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25TokenDictionaryMapperContractTest {

    private static final Path MAPPER = Path.of(
            "src", "main", "resources", "mapper", "Bm25TokenDictionaryMapper.xml"
    );

    @Test
    void shouldAtomicallyReturnStableIdsForNewAndExistingTokens() throws Exception {
        assertThat(Files.exists(MAPPER)).isTrue();

        String mapper = Files.readString(MAPPER)
                .toLowerCase()
                .replaceAll("\\s+", " ");

        assertThat(mapper)
                .contains("insert into rag_bm25_token_dictionary")
                .contains("on conflict (token) do update")
                .contains("set token = rag_bm25_token_dictionary.token")
                .contains("returning token, token_id")
                .contains("collection=\"tokens\"")
                .contains("resultmap=\"tokenentryresultmap\"");
    }
}
