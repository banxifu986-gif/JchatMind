package com.kama.jchatmind.rag;

import com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper;
import com.kama.jchatmind.model.dto.Bm25TokenDictionaryEntry;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VchordBm25ProjectionServiceTest {

    @Test
    void shouldBuildFrequencyVectorsFromStableTokenIds() {
        Bm25TokenDictionaryMapper tokenDictionaryMapper = mock(Bm25TokenDictionaryMapper.class);
        when(tokenDictionaryMapper.upsertTokens(List.of("rag", "向量", "接口", "量接"))).thenReturn(List.of(
                new Bm25TokenDictionaryEntry("接口", 42L),
                new Bm25TokenDictionaryEntry("rag", 11L),
                new Bm25TokenDictionaryEntry("量接", 96L),
                new Bm25TokenDictionaryEntry("向量", 73L)
        ));
        VchordBm25ProjectionService projectionService = new VchordBm25ProjectionService(tokenDictionaryMapper);

        VchordBm25ProjectionService.Projection projection = projectionService.project(
                "RAG 接口 接口",
                "向量接口 RAG"
        );

        assertThat(projection.titleVector()).isEqualTo("{11:1,42:2}");
        assertThat(projection.contentVector()).isEqualTo("{11:1,42:1,73:1,96:1}");
        assertThat(projection.indexVersion()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyVectorsWithoutWritingDictionaryForEmptyText() {
        Bm25TokenDictionaryMapper tokenDictionaryMapper = mock(Bm25TokenDictionaryMapper.class);
        VchordBm25ProjectionService projectionService = new VchordBm25ProjectionService(tokenDictionaryMapper);

        VchordBm25ProjectionService.Projection projection = projectionService.project("", "");

        assertThat(projection.titleVector()).isEqualTo("{}");
        assertThat(projection.contentVector()).isEqualTo("{}");
        verifyNoInteractions(tokenDictionaryMapper);
    }

    @Test
    void shouldUpsertUniqueTokensInStableLexicalOrder() {
        Bm25TokenDictionaryMapper tokenDictionaryMapper = mock(Bm25TokenDictionaryMapper.class);
        when(tokenDictionaryMapper.upsertTokens(anyList())).thenReturn(List.of(
                new Bm25TokenDictionaryEntry("rag", 11L),
                new Bm25TokenDictionaryEntry("向量", 42L),
                new Bm25TokenDictionaryEntry("接口", 73L),
                new Bm25TokenDictionaryEntry("量接", 96L)
        ));
        VchordBm25ProjectionService projectionService = new VchordBm25ProjectionService(tokenDictionaryMapper);

        projectionService.project("RAG 接口", "向量接口 RAG");

        ArgumentCaptor<List<String>> tokensCaptor = ArgumentCaptor.forClass(List.class);
        verify(tokenDictionaryMapper).upsertTokens(tokensCaptor.capture());
        assertThat(tokensCaptor.getValue()).containsExactly("rag", "向量", "接口", "量接");
    }
}
