package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.VchordBm25BackfillMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VchordBm25BackfillServiceTest {

    @Test
    void shouldProjectOnlyLockedLegacyChunksInTheRequestedBoundedBatch() {
        VchordBm25BackfillMapper backfillMapper = mock(VchordBm25BackfillMapper.class);
        VchordBm25ProjectionService projectionService = mock(VchordBm25ProjectionService.class);
        ChunkBgeM3 legacyChunk = ChunkBgeM3.builder()
                .id("chunk-1")
                .content("正文检索内容")
                .metadata("{\"retrievableTitleSearchText\":\"RAG 接口设计\"}")
                .build();
        when(backfillMapper.selectLegacyBm25ChunksForUpdate(10)).thenReturn(List.of(legacyChunk));
        when(projectionService.project("RAG 接口设计", "正文检索内容")).thenReturn(
                new VchordBm25ProjectionService.Projection("{11:2}", "{42:1}", 1)
        );
        when(backfillMapper.updateBm25Projection("chunk-1", "{11:2}", "{42:1}", 1)).thenReturn(1);
        VchordBm25BackfillService service = new VchordBm25BackfillService(
                backfillMapper,
                projectionService,
                new ObjectMapper()
        );

        int backfilled = service.backfill(10);

        assertThat(backfilled).isEqualTo(1);
        verify(backfillMapper).selectLegacyBm25ChunksForUpdate(10);
        verify(projectionService).project("RAG 接口设计", "正文检索内容");
        verify(backfillMapper).updateBm25Projection("chunk-1", "{11:2}", "{42:1}", 1);
    }

    @Test
    void shouldRejectTheBatchWhenALockedLegacyChunkCannotBeUpdated() {
        VchordBm25BackfillMapper backfillMapper = mock(VchordBm25BackfillMapper.class);
        VchordBm25ProjectionService projectionService = mock(VchordBm25ProjectionService.class);
        ChunkBgeM3 legacyChunk = ChunkBgeM3.builder()
                .id("chunk-1")
                .content("正文检索内容")
                .metadata("{}")
                .build();
        when(backfillMapper.selectLegacyBm25ChunksForUpdate(1)).thenReturn(List.of(legacyChunk));
        when(projectionService.project("", "正文检索内容")).thenReturn(
                new VchordBm25ProjectionService.Projection("{}", "{42:1}", 1)
        );
        when(backfillMapper.updateBm25Projection("chunk-1", "{}", "{42:1}", 1)).thenReturn(0);
        VchordBm25BackfillService service = new VchordBm25BackfillService(
                backfillMapper,
                projectionService,
                new ObjectMapper()
        );

        assertThatThrownBy(() -> service.backfill(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("更新历史分块失败");
    }

    @Test
    void shouldRejectOutOfRangeBatchBeforeClaimingAnyHistoricalChunk() {
        VchordBm25BackfillMapper backfillMapper = mock(VchordBm25BackfillMapper.class);
        VchordBm25BackfillService service = new VchordBm25BackfillService(
                backfillMapper,
                mock(VchordBm25ProjectionService.class),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> service.backfill(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("批量");
        assertThatThrownBy(() -> service.backfill(VchordBm25BackfillService.MAX_BATCH_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("批量");
        verify(backfillMapper, never()).selectLegacyBm25ChunksForUpdate(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldNotStartBackfillWhenTheExplicitServiceIsConstructed() {
        VchordBm25BackfillMapper backfillMapper = mock(VchordBm25BackfillMapper.class);
        VchordBm25ProjectionService projectionService = mock(VchordBm25ProjectionService.class);

        new VchordBm25BackfillService(backfillMapper, projectionService, new ObjectMapper());

        verifyNoInteractions(backfillMapper, projectionService);
    }
}
