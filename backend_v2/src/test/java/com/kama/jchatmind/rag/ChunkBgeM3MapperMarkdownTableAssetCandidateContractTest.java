package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBgeM3MapperMarkdownTableAssetCandidateContractTest {

    @Test
    void shouldQueryReadyMarkdownTableAssetsWithExactCandidateAssetMetadata() throws IOException {
        String mapper = Files.readString(Path.of("src/main/resources/mapper/ChunkBgeM3Mapper.xml"));
        String select = selectClause(mapper, "similaritySearchMarkdownTableAssets");

        assertTrue(!select.isEmpty(), "必须提供 Markdown 表格资产候选查询");
        assertTrue(select.contains("<include refid=\"KbIdsWhereClause\"/>"), "表格资产候选必须保留 KB 范围条件");
        assertTrue(select.contains("JOIN document_asset_chunk"), "表格资产候选必须通过资产与 chunk 的关系表查询");
        assertTrue(select.contains("JOIN document_asset"), "表格资产候选必须校验资产实体");
        assertTrue(select.contains("asset.asset_type = 'TABLE'"), "表格资产候选只应返回 TABLE 资产");
        assertTrue(select.contains("asset.status = 'READY'"), "表格资产候选不能返回未完成或失败资产");
        assertTrue(select.contains("jsonb_set"), "候选 metadata 必须覆盖为当前关联资产");
        assertTrue(select.contains("jsonb_build_object"), "候选 metadata 必须结构化写入资产标识");
        assertTrue(select.contains("'id', asset.asset_id"), "候选 metadata 必须包含精确资产 ID");
        assertTrue(select.contains("'type', asset.asset_type"), "候选 metadata 必须包含精确资产类型");
        assertTrue(select.contains("'locator', asset.locator"), "候选 metadata 必须包含精确资产定位信息");
        assertTrue(select.contains("ORDER BY chunk.embedding"), "表格资产候选必须按向量距离排序");
        assertTrue(select.contains("asset.asset_key ASC"), "同一 chunk 的多个表格资产必须使用稳定次级排序");
        assertTrue(select.contains("asset.asset_id ASC"), "同一表格键的候选必须使用稳定资产 ID 次级排序");
    }

    @Test
    void shouldReusePdfContentPathEscapingForMarkdownTableAssets() throws IOException {
        String mapper = Files.readString(Path.of("src/main/resources/mapper/ChunkBgeM3Mapper.xml"));
        String pdfSelect = selectClause(mapper, "similaritySearchPdfPageAssets");
        String tableSelect = selectClause(mapper, "similaritySearchMarkdownTableAssets");

        assertTrue(!pdfSelect.isEmpty(), "必须提供 PDF 页文本资产候选查询作为路径过滤基准");
        assertTrue(!tableSelect.isEmpty(), "必须提供 Markdown 表格资产候选查询");
        assertEquals(
                contentPathPrefixClause(pdfSelect),
                contentPathPrefixClause(tableSelect),
                "Markdown TABLE 候选必须复用 PDF 已验证的 contentPath 归一化和 LIKE 转义规则"
        );
    }

    private String selectClause(String mapper, String statementId) {
        int start = mapper.indexOf("<select id=\"" + statementId + "\"");
        int end = mapper.indexOf("</select>", start);
        return start < 0 || end < 0 ? "" : mapper.substring(start, end);
    }

    private String contentPathPrefixClause(String select) {
        int start = select.indexOf("<if test=\"contentPathPrefix != null and contentPathPrefix != ''\">");
        int end = select.indexOf("</if>", start);
        return start < 0 || end < 0 ? "" : select.substring(start, end);
    }
}
