package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.Bm25TokenDictionaryEntry;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VchordBm25QueryServiceTest {

    @Test
    void shouldBuildReadOnlyQueryVectorAndSetLocalSearchPathBeforeContentSearch() {
        Bm25TokenDictionaryMapper tokenDictionaryMapper = mock(Bm25TokenDictionaryMapper.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagRetrievalResult candidate = new RagRetrievalResult();
        candidate.setChunkId("chunk-1");
        candidate.setRank(1);
        when(tokenDictionaryMapper.selectTokenIds(List.of("rag", "接口"))).thenReturn(List.of(
                new Bm25TokenDictionaryEntry("rag", 11L),
                new Bm25TokenDictionaryEntry("接口", 42L)
        ));
        when(chunkBgeM3Mapper.searchByContentBm25(
                List.of("kb-1"),
                "{11:1,42:1}",
                "architecture.md",
                "md",
                "rag > bm25",
                1,
                20
        )).thenReturn(List.of(candidate));
        VchordBm25QueryService service = new VchordBm25QueryService(
                tokenDictionaryMapper,
                chunkBgeM3Mapper,
                jdbcTemplate
        );

        List<RagRetrievalResult> results = service.searchContent(
                List.of("kb-1"),
                "RAG 接口",
                "architecture.md",
                "md",
                "rag > bm25",
                20
        );

        assertThat(results).containsExactly(candidate);
        assertThat(candidate.getContentBm25Rank()).isEqualTo(1);
        assertThat(candidate.getContentBm25Score()).isNull();
        verify(jdbcTemplate).execute("SET LOCAL search_path = bm25_catalog, pg_catalog, public");
        verify(tokenDictionaryMapper).selectTokenIds(List.of("rag", "接口"));
        verify(tokenDictionaryMapper, never()).upsertTokens(org.mockito.ArgumentMatchers.anyList());
        verify(chunkBgeM3Mapper).searchByContentBm25(
                List.of("kb-1"),
                "{11:1,42:1}",
                "architecture.md",
                "md",
                "rag > bm25",
                1,
                20
        );
    }

    @Test
    void shouldNotWriteUnknownQueryTokensOrSearchWithoutKnownTokenIds() {
        Bm25TokenDictionaryMapper tokenDictionaryMapper = mock(Bm25TokenDictionaryMapper.class);
        ChunkBgeM3Mapper chunkBgeM3Mapper = mock(ChunkBgeM3Mapper.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(tokenDictionaryMapper.selectTokenIds(List.of("unknown"))).thenReturn(List.of());
        VchordBm25QueryService service = new VchordBm25QueryService(
                tokenDictionaryMapper,
                chunkBgeM3Mapper,
                jdbcTemplate
        );

        List<RagRetrievalResult> results = service.searchTitle(
                List.of("kb-1"),
                "unknown",
                null,
                null,
                null,
                10
        );

        assertThat(results).isEmpty();
        verify(tokenDictionaryMapper).selectTokenIds(List.of("unknown"));
        verify(tokenDictionaryMapper, never()).upsertTokens(org.mockito.ArgumentMatchers.anyList());
        verify(chunkBgeM3Mapper, never()).searchByTitleBm25(
                List.of("kb-1"),
                "{}",
                null,
                null,
                null,
                1,
                10
        );
        verify(jdbcTemplate, never()).execute(anyString());
    }
}
