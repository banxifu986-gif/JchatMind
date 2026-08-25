package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBgeM3MapperPdfAssetCandidateContractTest {

    @Test
    void shouldQueryReadyPdfPageAssetsThroughAssetRelationsWithinKnowledgeBaseScope() throws IOException {
        String mapper = Files.readString(Path.of("src/main/resources/mapper/ChunkBgeM3Mapper.xml"));
        int start = mapper.indexOf("<select id=\"similaritySearchPdfPageAssets\"");
        int end = mapper.indexOf("</select>", start);
        String select = start < 0 || end < 0 ? "" : mapper.substring(start, end);

        assertTrue(start >= 0, "必须提供 PDF 页资产候选查询");
        assertTrue(select.contains("<include refid=\"KbIdsWhereClause\"/>"), "资产候选必须保留 KB 范围条件");
        assertTrue(select.contains("JOIN document_asset_chunk"), "资产候选必须通过资产与 chunk 的关系表查询");
        assertTrue(select.contains("JOIN document_asset"), "资产候选必须校验资产实体");
        assertTrue(select.contains("asset.asset_type = 'PDF_PAGE_TEXT'"), "资产候选只应返回 PDF 页文本资产");
        assertTrue(select.contains("asset.status = 'READY'"), "资产候选不能返回未完成或失败资产");
        int assetFilterCdata = select.indexOf("<![CDATA[", select.indexOf("<include refid=\"KbIdsWhereClause\"/>"));
        assertTrue(select.indexOf("]]>", assetFilterCdata) < select.indexOf("<if test=\"sourceName"),
                "资产候选的动态范围条件必须位于 CDATA 外，由 MyBatis 解析");
        assertTrue(select.contains("ORDER BY chunk.embedding"), "资产候选必须按向量距离排序");
    }
}
