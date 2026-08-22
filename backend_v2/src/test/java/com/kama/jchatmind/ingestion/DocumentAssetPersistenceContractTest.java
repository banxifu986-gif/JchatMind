package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentAssetPersistenceContractTest {

    private static final Path ENTITY = Path.of(
            "src", "main", "java", "com", "kama", "jchatmind", "model", "entity", "DocumentAsset.java"
    );
    private static final Path MAPPER = Path.of(
            "src", "main", "java", "com", "kama", "jchatmind", "mapper", "DocumentAssetMapper.java"
    );
    private static final Path MAPPER_XML = Path.of(
            "src", "main", "resources", "mapper", "DocumentAssetMapper.xml"
    );

    @Test
    void shouldDefineDocumentAssetAndSameDocumentRelationPersistenceOperations() throws Exception {
        assertThat(Files.exists(ENTITY)).isTrue();
        assertThat(Files.exists(MAPPER)).isTrue();
        assertThat(Files.exists(MAPPER_XML)).isTrue();

        String entity = Files.readString(ENTITY);
        String mapper = Files.readString(MAPPER);
        String mapperXml = Files.readString(MAPPER_XML).toLowerCase();

        assertThat(entity)
                .contains("class DocumentAsset")
                .contains("assetId")
                .contains("documentId")
                .contains("assetType")
                .contains("assetKey")
                .contains("pageNumber")
                .contains("locator")
                .contains("contentHash")
                .contains("parserVersion")
                .contains("status");
        assertThat(mapper)
                .contains("insert(DocumentAsset")
                .contains("deleteByDocumentId")
                .contains("insertChunkRelation")
                .contains("assetDocumentId")
                .contains("chunkDocumentId");
        assertThat(mapperXml)
                .contains("insert into document_asset")
                .contains("asset_id")
                .contains("cast(#{locator} as jsonb)")
                .contains("delete from document_asset")
                .contains("insert into document_asset_chunk")
                .contains("asset_document_id")
                .contains("chunk_document_id")
                .contains("cast(#{assetid} as uuid)")
                .contains("cast(#{chunkid} as uuid)")
                .contains("cast(#{assetdocumentid} as uuid)")
                .contains("cast(#{chunkdocumentid} as uuid)");
    }

    @Test
    void shouldKeepMapperBindingsAndDefaultLocatorContract() throws Exception {
        String mapper = Files.readString(MAPPER);
        String mapperXml = Files.readString(MAPPER_XML).toLowerCase();

        assertThat(mapper)
                .contains("@Param(\"assetId\")")
                .contains("@Param(\"chunkId\")")
                .contains("@Param(\"assetDocumentId\")")
                .contains("@Param(\"chunkDocumentId\")");
        assertThat(mapperXml)
                .contains("<mapper namespace=\"com.kama.jchatmind.mapper.documentassetmapper\">")
                .contains("<insert id=\"insert\"")
                .contains("<delete id=\"deletebydocumentid\"")
                .contains("<insert id=\"insertchunkrelation\"")
                .contains("coalesce(cast(#{locator} as jsonb), '{}'::jsonb)")
                .contains("cast(#{assetid} as uuid)")
                .contains("cast(#{documentid} as uuid)")
                .contains("cast(#{chunkid} as uuid)")
                .contains("cast(#{assetdocumentid} as uuid)")
                .contains("cast(#{chunkdocumentid} as uuid)");
    }
}
