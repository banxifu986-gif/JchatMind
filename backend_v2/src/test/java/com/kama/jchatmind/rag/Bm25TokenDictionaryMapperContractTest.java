package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25TokenDictionaryMapperContractTest {

    private static final Path MAPPER = Path.of(
            "src", "main", "resources", "mapper", "Bm25TokenDictionaryMapper.xml"
    );
    private static final Path MAPPER_INTERFACE = Path.of(
            "src", "main", "java", "com", "kama", "jchatmind", "mapper", "Bm25TokenDictionaryMapper.java"
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

    @Test
    void shouldReadKnownTokenIdsWithoutWritingUnknownQueryTerms() throws Exception {
        String mapper = Files.readString(MAPPER).toLowerCase();
        String mapperInterface = Files.readString(MAPPER_INTERFACE);

        assertThat(mapperInterface).contains("selectTokenIds");
        assertThat(mapper).contains("<select id=\"selecttokenids\"");

        int queryStart = mapper.indexOf("<select id=\"selecttokenids\"");
        int queryEnd = mapper.indexOf("</select>", queryStart);
        String readQuery = mapper.substring(queryStart, queryEnd);
        assertThat(readQuery)
                .contains("select token, token_id")
                .contains("where token in")
                .doesNotContain("insert into")
                .doesNotContain("on conflict");
    }
}
