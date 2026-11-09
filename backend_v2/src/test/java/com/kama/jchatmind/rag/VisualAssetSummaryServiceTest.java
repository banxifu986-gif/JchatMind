package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.DocumentAssetExtractionMapper;
import com.kama.jchatmind.model.dto.VisualAssetSummaryItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VisualAssetSummaryServiceTest {

    private final DocumentAssetExtractionMapper mapper = mock(DocumentAssetExtractionMapper.class);
    private final VisualAssetSummaryService service =
            new VisualAssetSummaryService(mapper, new ObjectMapper());

    @Test
    void shouldReturnNullWhenIntentSummaryDisabled() {
        ReflectionTestUtils.setField(service, "intentSummaryEnabled", false);

        assertThat(service.summarize(List.of("kb-1"))).isNull();
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldBuildCompactSummaryLines() {
        ReflectionTestUtils.setField(service, "intentSummaryEnabled", true);
        VisualAssetSummaryItem item = new VisualAssetSummaryItem();
        item.setVisualKind("DIAGRAM");
        item.setLocator("{\"sectionPath\":\"部署架构 > 服务调用\"}");
        item.setCaption("订单服务调用库存服务的架构图");
        item.setEntityTypes("SERVICE,SYSTEM");
        when(mapper.selectActiveVisualAssetSummaries(anyList(), anyInt())).thenReturn(List.of(item));
        when(mapper.hasActiveVisualAsset(anyList())).thenReturn(true);

        String summary = service.summarize(List.of("kb-1"));

        assertThat(summary)
                .contains("- DIAGRAM @部署架构 > 服务调用")
                .contains("实体类型[SERVICE,SYSTEM]")
                .contains("订单服务调用库存服务的架构图");
        assertThat(service.hasVisualAssets(List.of("kb-1"))).isTrue();
    }

    @Test
    void shouldReturnNullWhenNoActiveAssets() {
        ReflectionTestUtils.setField(service, "intentSummaryEnabled", true);
        when(mapper.selectActiveVisualAssetSummaries(anyList(), anyInt())).thenReturn(List.of());

        assertThat(service.summarize(List.of("kb-1"))).isNull();
    }
}