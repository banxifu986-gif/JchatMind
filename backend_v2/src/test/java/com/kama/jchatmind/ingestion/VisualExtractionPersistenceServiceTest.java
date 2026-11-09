package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentAssetEntityMapper;
import com.kama.jchatmind.mapper.DocumentAssetExtractionMapper;
import com.kama.jchatmind.mapper.DocumentAssetMapper;
import com.kama.jchatmind.mapper.DocumentAssetRelationMapper;
import com.kama.jchatmind.model.dto.DocumentImageAssetCandidate;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.DocumentAssetEntity;
import com.kama.jchatmind.model.entity.DocumentAssetExtraction;
import com.kama.jchatmind.model.entity.DocumentAssetRelation;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class VisualExtractionPersistenceServiceTest {

    private final DocumentAssetExtractionMapper extractionMapper = mock(DocumentAssetExtractionMapper.class);
    private final DocumentAssetEntityMapper entityMapper = mock(DocumentAssetEntityMapper.class);
    private final DocumentAssetRelationMapper relationMapper = mock(DocumentAssetRelationMapper.class);
    private final ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
    private final DocumentAssetMapper documentAssetMapper = mock(DocumentAssetMapper.class);
    private final RagService ragService = mock(RagService.class);
    private final VchordBm25ProjectionService projectionService = mock(VchordBm25ProjectionService.class);
    private final VisualExtractionPersistenceService service = new VisualExtractionPersistenceService(
            extractionMapper,
            entityMapper,
            relationMapper,
            chunkMapper,
            documentAssetMapper,
            ragService,
            projectionService,
            new ObjectMapper()
    );

    @Test
    void shouldPersistExtractionEntitiesRelationsAndVisualChunk() {
        DocumentImageAssetCandidate candidate = candidate();
        DocumentAssetExtraction extraction = extraction();
        when(extractionMapper.markActive(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(ragService.embed(any())).thenReturn(new float[]{0.1F});
        when(projectionService.project(any(), any())).thenReturn(
                new VchordBm25ProjectionService.Projection("{1:1}", "{2:1}", 1)
        );
        when(chunkMapper.insert(any(ChunkBgeM3.class))).thenAnswer(invocation -> {
            invocation.<ChunkBgeM3>getArgument(0).setId("chunk-1");
            return 1;
        });
        when(entityMapper.insert(any(DocumentAssetEntity.class))).thenReturn(1);
        when(relationMapper.insert(any(DocumentAssetRelation.class))).thenReturn(1);
        when(documentAssetMapper.insertChunkRelation(any(), any(), any(), any())).thenReturn(1);
        when(relationMapper.insert(any(DocumentAssetRelation.class))).thenReturn(1);

        service.persist(candidate, extraction, result());

        verify(extractionMapper).supersedeActiveByAssetId(eq("asset-1"), any());
        verify(extractionMapper).markActive(
                eq("extraction-1"), eq("架构图"), eq("A->B"), eq("s"), eq("DIAGRAM"), eq(0.9), eq(null), any());
        ArgumentCaptor<DocumentAssetEntity> entityCaptor = ArgumentCaptor.forClass(DocumentAssetEntity.class);
        verify(entityMapper).insert(entityCaptor.capture());
        assertThat(entityCaptor.getValue())
                .extracting(DocumentAssetEntity::getEntityKey, DocumentAssetEntity::getName, DocumentAssetEntity::getEntityType)
                .containsExactly("a", "A", "SERVICE");
        ArgumentCaptor<DocumentAssetRelation> relationCaptor = ArgumentCaptor.forClass(DocumentAssetRelation.class);
        verify(relationMapper).insert(relationCaptor.capture());
        assertThat(relationCaptor.getValue())
                .extracting(DocumentAssetRelation::getPredicate, DocumentAssetRelation::getEvidenceText)
                .containsExactly("CALLS", "箭头");
        ArgumentCaptor<ChunkBgeM3> chunkCaptor = ArgumentCaptor.forClass(ChunkBgeM3.class);
        verify(chunkMapper).insert(chunkCaptor.capture());
        ChunkBgeM3 chunk = chunkCaptor.getValue();
        assertThat(chunk.getKbId()).isEqualTo("kb-1");
        assertThat(chunk.getContent()).contains("架构图").contains("A(SERVICE)").contains("a -> CALLS -> a");
        assertThat(chunk.getMetadata()).contains("visualKind").contains("extraction-1").contains("asset-1");
        verify(documentAssetMapper).insertChunkRelation("asset-1", "chunk-1", "doc-1", "doc-1");
    }

    @Test
    void shouldNotInsertChunkRelationWhenChunkInsertFails() {
        when(chunkMapper.insert(any(ChunkBgeM3.class))).thenReturn(0);
        when(ragService.embed(any())).thenReturn(new float[]{0.1F});
        when(projectionService.project(any(), any())).thenReturn(
                new VchordBm25ProjectionService.Projection(null, null, null)
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.persist(candidate(), extraction(), result())
                )
                .isInstanceOf(com.kama.jchatmind.exception.BizException.class);

        verify(documentAssetMapper, never()).insertChunkRelation(any(), any(), any(), any());
    }

    private DocumentImageAssetCandidate candidate() {
        DocumentImageAssetCandidate candidate = new DocumentImageAssetCandidate();
        candidate.setAssetId("asset-1");
        candidate.setDocumentId("doc-1");
        candidate.setKbId("kb-1");
        candidate.setPageNumber(1);
        candidate.setLocator("{\"source\":\"PDF\",\"pageNumber\":1}");
        candidate.setFilename("report.pdf");
        candidate.setFiletype("pdf");
        return candidate;
    }

    private DocumentAssetExtraction extraction() {
        return DocumentAssetExtraction.builder()
                .extractionId("extraction-1")
                .assetId("asset-1")
                .documentId("doc-1")
                .status("PENDING")
                .build();
    }

    private VisualExtractionResult result() {
        return new VisualExtractionResult(
                "架构图",
                "A->B",
                "s",
                "DIAGRAM",
                0.9,
                List.of(new VisualExtractionResult.VisualEntity("a", "A", "SERVICE", new double[]{1, 2, 3, 4}, 0.9)),
                List.of(new VisualExtractionResult.VisualRelation("a", "CALLS", "a", "箭头", 0.8))
        );
    }
}