package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import com.kama.jchatmind.util.RagChunkSupport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class G2CurrentMultimodalCandidateFixtureLoader {

    private static final String DATASET_ID = "g2-current-multimodal-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private G2CurrentMultimodalCandidateFixtureLoader() {
    }

    static CandidateProfile load(String manifestPath) throws IOException {
        byte[] manifestBytes = readBytes(manifestPath);
        JsonNode manifest = OBJECT_MAPPER.readTree(manifestBytes);
        validateManifest(manifest);
        String manifestSha256 = sha256(manifestBytes);

        String casePath = resolveSiblingPath(manifestPath, manifest.path("caseFile").asText());
        byte[] caseBytes = readBytes(casePath);
        if (!sha256(caseBytes).equals(manifest.path("caseSha256").asText())) {
            throw new IllegalStateException("multimodal candidate case SHA-256 不匹配");
        }
        List<CandidateCase> cases = parseCases(caseBytes, manifest.path("datasetId").asText());

        List<String> corpusFiles = strings(manifest.path("corpusFiles")).stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        StringBuilder corpusFingerprint = new StringBuilder();
        Map<String, byte[]> corpusBytesByFileName = new LinkedHashMap<>();
        for (String relativePath : corpusFiles) {
            byte[] bytes = readBytes(resolveSiblingPath(manifestPath, relativePath));
            String fileName = fileName(relativePath);
            corpusBytesByFileName.put(fileName, bytes);
            corpusFingerprint.append(fileName).append('\n')
                    .append(new String(bytes, StandardCharsets.UTF_8)).append('\n');
        }
        if (!sha256(corpusFingerprint.toString().getBytes(StandardCharsets.UTF_8))
                .equals(manifest.path("corpusSha256").asText())) {
            throw new IllegalStateException("multimodal candidate corpus SHA-256 不匹配");
        }
        validateRouterExpectations(cases);

        MarkdownParserService parser = new MarkdownParserServiceImpl();
        List<CandidateAsset> assets = new ArrayList<>();
        for (String relativePath : corpusFiles) {
            String fileName = fileName(relativePath);
            String sourceDocumentId = sourceDocumentIdForFile(cases, fileName);
            byte[] bytes = corpusBytesByFileName.get(fileName);
            assets.addAll(parseMarkdownAssets(parser, sourceDocumentId, fileName, bytes, manifestSha256));
        }
        for (CandidateCase candidateCase : cases) {
            if (StringUtils.hasText(candidateCase.sourceGenerator())) {
                assets.addAll(parseGeneratedPdfAssets(parser, candidateCase, manifestSha256));
            }
        }
        validateGoldCases(cases, assets);
        AssetImportPlan assetImportPlan = prepareAssetImportPlan(
                manifest.path("datasetId").asText(),
                manifestSha256,
                assets
        );

        return new CandidateProfile(
                manifest.path("datasetId").asText(),
                manifest.path("status").asText(),
                manifestSha256,
                manifest.path("caseSha256").asText(),
                manifest.path("corpusSha256").asText(),
                List.copyOf(cases),
                List.copyOf(assets),
                assetImportPlan
        );
    }

    private static void validateManifest(JsonNode manifest) {
        if (!DATASET_ID.equals(manifest.path("datasetId").asText())
                || !"1".equals(manifest.path("schemaVersion").asText())
                || !"candidate".equals(manifest.path("status").asText())
                || !StringUtils.hasText(manifest.path("caseFile").asText())
                || !StringUtils.hasText(manifest.path("caseSha256").asText())
                || !StringUtils.hasText(manifest.path("corpusSha256").asText())
                || manifest.path("defaultTopK").asInt() <= 0
                || !manifest.path("corpusFiles").isArray()
                || manifest.path("corpusFiles").isEmpty()) {
            throw new IllegalStateException("multimodal candidate manifest 不符合 schema v1");
        }
    }

    private static List<CandidateCase> parseCases(byte[] caseBytes, String datasetId) throws IOException {
        List<CandidateCase> cases = new ArrayList<>();
        for (String line : new String(caseBytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = OBJECT_MAPPER.readTree(line);
            GoldAsset goldAsset = node.path("goldAsset").isNull()
                    ? null
                    : new GoldAsset(
                            node.path("goldAsset").path("assetType").asText(),
                            node.path("goldAsset").path("assetKey").asText(),
                            integerMap(node.path("goldAsset").path("locator"))
                    );
            CandidateCase candidateCase = new CandidateCase(
                    node.path("caseId").asText(),
                    node.path("datasetId").asText(),
                    node.path("query").asText(),
                    node.path("queryType").asText(),
                    strings(node.path("kbScope")),
                    node.path("expectedRoute").asText(),
                    node.path("sourceDocumentId").asText(),
                    nullableText(node.path("sourceFile")),
                    nullableText(node.path("sourceGenerator")),
                    nullableText(node.path("sourceText")),
                    nullableText(node.path("sourceTextSha256")),
                    nullableText(node.path("goldChunkLogicalId")),
                    goldAsset,
                    nullableText(node.path("goldEvidence")),
                    node.path("shouldAbstain").asBoolean(),
                    node.path("reviewStatus").asText()
            );
            if (!datasetId.equals(candidateCase.datasetId())
                    || !StringUtils.hasText(candidateCase.caseId())
                    || !StringUtils.hasText(candidateCase.query())
                    || candidateCase.kbScope().isEmpty()
                    || !StringUtils.hasText(candidateCase.expectedRoute())
                    || !StringUtils.hasText(candidateCase.sourceDocumentId())
                    || !"pending".equals(candidateCase.reviewStatus())) {
                throw new IllegalStateException("multimodal candidate case 缺少字段、数据集不匹配或已越过待审核状态");
            }
            cases.add(candidateCase);
        }
        if (cases.isEmpty() || cases.stream().map(CandidateCase::caseId).distinct().count() != cases.size()) {
            throw new IllegalStateException("multimodal candidate case 为空或 caseId 重复");
        }
        return List.copyOf(cases);
    }

    private static void validateRouterExpectations(List<CandidateCase> cases) {
        RagRouter router = new RagRouter();
        for (CandidateCase candidateCase : cases) {
            String actualRoute = router.decide(
                    candidateCase.query(),
                    candidateCase.kbScope(),
                    true,
                    false,
                    true
            ).route().name();
            if (!actualRoute.equals(candidateCase.expectedRoute())) {
                throw new IllegalStateException("multimodal candidate Router route 不匹配: "
                        + candidateCase.caseId() + " expected=" + candidateCase.expectedRoute()
                        + " actual=" + actualRoute);
            }
        }
    }

    private static List<CandidateAsset> parseMarkdownAssets(
            MarkdownParserService parser,
            String sourceDocumentId,
            String sourceName,
            byte[] sourceBytes,
            String manifestSha256
    ) {
        List<MarkdownParserService.MarkdownSection> sections = parser.parseMarkdown(
                new ByteArrayInputStream(sourceBytes)
        );
        Map<MarkdownParserService.MarkdownSection, String> logicalChunkIds = logicalChunkIds(
                sourceDocumentId,
                sections
        );
        List<MarkdownParserService.MarkdownTable> tables = parser.parseMarkdownTables(
                new ByteArrayInputStream(sourceBytes)
        );
        List<CandidateAsset> assets = new ArrayList<>();
        for (int index = 0; index < tables.size(); index++) {
            MarkdownParserService.MarkdownTable table = tables.get(index);
            List<MarkdownParserService.MarkdownSection> matchingSections = sections.stream()
                    .filter(section -> section.getContent().contains(table.content()))
                    .toList();
            if (matchingSections.size() != 1) {
                throw new IllegalStateException("multimodal table 必须唯一映射到一个 chunk: " + sourceName);
            }
            String assetKey = "table-" + (index + 1);
            String logicalChunkId = logicalChunkIds.get(matchingSections.get(0));
            int sectionIndex = identityIndexOf(sections, matchingSections.get(0));
            String runtimeAssetUuid = deterministicAssetUuid(
                    manifestSha256,
                    sourceDocumentId,
                    "TABLE",
                    assetKey
            );
            Map<String, Integer> locator = Map.of("startLine", table.startLine(), "endLine", table.endLine());
            MarkdownParserService.MarkdownSection section = matchingSections.get(0);
            assets.add(new CandidateAsset(
                    sourceDocumentId,
                    deterministicRuntimeUuid(manifestSha256, "document", sourceDocumentId),
                    logicalChunkId,
                    deterministicRuntimeUuid(manifestSha256, "chunk", logicalChunkId),
                    runtimeAssetUuid,
                    "TABLE",
                    assetKey,
                    locator,
                    table.content(),
                    sha256(table.content().getBytes(StandardCharsets.UTF_8)),
                    chunkPayload(section, "md", sourceName, sourceBytes.length, sectionIndex,
                            runtimeAssetUuid, "TABLE", locator)
            ));
        }
        return assets;
    }

    private static String sourceDocumentIdForFile(List<CandidateCase> cases, String fileName) {
        List<String> documentIds = cases.stream()
                .filter(item -> fileName.equals(item.sourceFile()))
                .map(CandidateCase::sourceDocumentId)
                .distinct()
                .toList();
        if (documentIds.size() != 1) {
            throw new IllegalStateException("multimodal corpus 文件必须唯一映射到来源文档: " + fileName);
        }
        return documentIds.get(0);
    }

    private static List<CandidateAsset> parseGeneratedPdfAssets(
            MarkdownParserService parser,
            CandidateCase candidateCase,
            String manifestSha256
    ) throws IOException {
        if (!"pdfbox-helvetica-text-v1".equals(candidateCase.sourceGenerator())
                || !StringUtils.hasText(candidateCase.sourceText())
                || !sha256(candidateCase.sourceText().getBytes(StandardCharsets.UTF_8))
                        .equals(candidateCase.sourceTextSha256())) {
            throw new IllegalStateException("multimodal PDF fixture source text/hash/generator 不匹配");
        }
        byte[] pdfBytes = generatePdf(candidateCase.sourceText());
        List<MarkdownParserService.MarkdownSection> pages = parser.parsePdf(new ByteArrayInputStream(pdfBytes));
        List<CandidateAsset> assets = new ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < pages.size(); sectionIndex++) {
            MarkdownParserService.MarkdownSection page = pages.get(sectionIndex);
            int pageNumber = page.getPageNumber();
            String assetKey = "page-" + pageNumber;
            String logicalChunkId = candidateCase.sourceDocumentId() + "#"
                    + normalizeContentPath(page.getContentPath()) + "#0";
            String runtimeAssetUuid = deterministicAssetUuid(
                    manifestSha256,
                    candidateCase.sourceDocumentId(),
                    "PDF_PAGE_TEXT",
                    assetKey
            );
            String sourceName = "generated-" + candidateCase.sourceGenerator() + ".pdf";
            Map<String, Integer> locator = Map.of("pageNumber", pageNumber);
            assets.add(new CandidateAsset(
                    candidateCase.sourceDocumentId(),
                    deterministicRuntimeUuid(manifestSha256, "document", candidateCase.sourceDocumentId()),
                    logicalChunkId,
                    deterministicRuntimeUuid(manifestSha256, "chunk", logicalChunkId),
                    runtimeAssetUuid,
                    "PDF_PAGE_TEXT",
                    assetKey,
                    locator,
                    page.getContent(),
                    sha256(page.getContent().getBytes(StandardCharsets.UTF_8)),
                    chunkPayload(page, "pdf", sourceName, pdfBytes.length, sectionIndex,
                            runtimeAssetUuid, "PDF_PAGE_TEXT", locator)
            ));
        }
        return assets;
    }

    private static void validateGoldCases(List<CandidateCase> cases, List<CandidateAsset> assets) {
        for (CandidateCase candidateCase : cases) {
            if (candidateCase.shouldAbstain()) {
                if (candidateCase.goldAsset() != null || candidateCase.goldChunkLogicalId() != null
                        || candidateCase.goldEvidence() != null) {
                    throw new IllegalStateException("multimodal abstention case 不能声明 gold evidence: "
                            + candidateCase.caseId());
                }
                continue;
            }
            if (candidateCase.goldAsset() == null || !StringUtils.hasText(candidateCase.goldChunkLogicalId())
                    || !StringUtils.hasText(candidateCase.goldEvidence())) {
                throw new IllegalStateException("multimodal answerable case 缺少 asset/chunk/evidence gold: "
                        + candidateCase.caseId());
            }
            CandidateAsset asset = assets.stream()
                    .filter(item -> item.sourceDocumentId().equals(candidateCase.sourceDocumentId()))
                    .filter(item -> item.assetType().equals(candidateCase.goldAsset().assetType()))
                    .filter(item -> item.assetKey().equals(candidateCase.goldAsset().assetKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("multimodal case gold asset 未映射: "
                            + candidateCase.caseId()));
            if (!asset.logicalChunkId().equals(candidateCase.goldChunkLogicalId())
                    || !asset.locator().equals(candidateCase.goldAsset().locator())
                    || !asset.content().contains(candidateCase.goldEvidence())) {
                throw new IllegalStateException("multimodal case gold chunk/locator/evidence 与资产不匹配: "
                        + candidateCase.caseId());
            }
        }
    }

    private static AssetImportPlan prepareAssetImportPlan(
            String datasetId,
            String manifestSha256,
            List<CandidateAsset> assets
    ) throws IOException {
        String knowledgeBaseId = deterministicRuntimeUuid(manifestSha256, "knowledge-base", datasetId);
        Map<String, Object> knowledgeBaseMetadata = Map.of(
                "evaluationNamespace", "rag-eval",
                "datasetId", datasetId,
                "manifestSha256", manifestSha256,
                "candidateOnly", true
        );
        Map<String, DocumentImportRow> documentRowsById = new LinkedHashMap<>();
        Map<String, ChunkImportRow> chunkRowsByLogicalId = new LinkedHashMap<>();
        List<AssetImportRow> assetRows = new ArrayList<>();
        List<AssetChunkRelation> relations = new ArrayList<>();
        Map<String, Map<String, Object>> chunkAssetMetadataByLogicalId = new LinkedHashMap<>();
        Map<String, CandidateAsset> assetsByRuntimeId = new LinkedHashMap<>();
        for (CandidateAsset asset : assets) {
            if (assetsByRuntimeId.putIfAbsent(asset.runtimeAssetUuid(), asset) != null) {
                throw new IllegalStateException("multimodal candidate runtime asset UUID 重复: "
                        + asset.runtimeAssetUuid());
            }
            CandidateChunk chunk = asset.chunkPayload();
            DocumentImportRow documentRow = new DocumentImportRow(
                    asset.runtimeDocumentUuid(),
                    knowledgeBaseId,
                    chunk.sourceName(),
                    chunk.sourceType(),
                    chunk.documentSizeBytes(),
                    Map.of(
                            "evaluationNamespace", "rag-eval",
                            "datasetId", datasetId,
                            "manifestSha256", manifestSha256,
                            "sourceDocumentId", asset.sourceDocumentId(),
                            "sourceName", chunk.sourceName()
                    )
            );
            DocumentImportRow previousDocument = documentRowsById.putIfAbsent(
                    asset.runtimeDocumentUuid(), documentRow
            );
            if (previousDocument != null && !previousDocument.equals(documentRow)) {
                throw new IllegalStateException("multimodal runtime document row 冲突: "
                        + asset.runtimeDocumentUuid());
            }

            Map<String, Object> chunkMetadata = new LinkedHashMap<>(chunk.metadata());
            chunkMetadata.remove("asset");
            chunkMetadata.put("evaluationNamespace", "rag-eval");
            chunkMetadata.put("datasetId", datasetId);
            chunkMetadata.put("manifestSha256", manifestSha256);
            chunkMetadata.put("sourceDocumentId", asset.sourceDocumentId());
            chunkMetadata.put("logicalChunkId", asset.logicalChunkId());
            ChunkImportRow chunkRow = new ChunkImportRow(
                    asset.runtimeChunkUuid(),
                    knowledgeBaseId,
                    asset.runtimeDocumentUuid(),
                    asset.logicalChunkId(),
                    chunk.content(),
                    Collections.unmodifiableMap(chunkMetadata),
                    chunk.embeddingText(),
                    chunk.titleSearchText()
            );
            ChunkImportRow previousChunk = chunkRowsByLogicalId.putIfAbsent(asset.logicalChunkId(), chunkRow);
            if (previousChunk != null && !previousChunk.equals(chunkRow)) {
                throw new IllegalStateException("multimodal runtime chunk row 冲突: " + asset.logicalChunkId());
            }

            Integer pageNumber = asset.locator().get("pageNumber");
            String parserVersion = switch (asset.assetType()) {
                case "TABLE" -> "markdown-table-v1";
                case "PDF_PAGE_TEXT" -> "pdf-text-v1";
                default -> throw new IllegalStateException("unsupported multimodal asset type: " + asset.assetType());
            };
            String locatorJson = OBJECT_MAPPER.writeValueAsString(new TreeMap<>(asset.locator()));
            assetRows.add(new AssetImportRow(
                    asset.runtimeAssetUuid(),
                    asset.runtimeDocumentUuid(),
                    asset.assetType(),
                    asset.assetKey(),
                    pageNumber,
                    locatorJson,
                    asset.contentHash(),
                    parserVersion,
                    "READY"
            ));
            relations.add(new AssetChunkRelation(
                    asset.runtimeAssetUuid(),
                    asset.runtimeChunkUuid(),
                    asset.runtimeDocumentUuid(),
                    asset.runtimeDocumentUuid()
            ));
            chunkAssetMetadataByLogicalId.putIfAbsent(
                    asset.logicalChunkId(),
                    Map.of("asset", Map.of(
                            "id", asset.runtimeAssetUuid(),
                            "type", asset.assetType(),
                            "locator", new TreeMap<>(asset.locator())
                    ))
            );
        }
        List<ChunkImportRow> chunkRows = chunkRowsByLogicalId.values().stream()
                .map(chunkRow -> {
                    Map<String, Object> metadata = new LinkedHashMap<>(chunkRow.metadata());
                    metadata.putAll(chunkAssetMetadataByLogicalId.getOrDefault(
                            chunkRow.logicalChunkId(), Map.of()
                    ));
                    return new ChunkImportRow(
                            chunkRow.chunkId(),
                            chunkRow.knowledgeBaseId(),
                            chunkRow.documentId(),
                            chunkRow.logicalChunkId(),
                            chunkRow.content(),
                            Collections.unmodifiableMap(metadata),
                            chunkRow.embeddingText(),
                            chunkRow.titleSearchText()
                    );
                })
                .sorted(java.util.Comparator.comparing(ChunkImportRow::logicalChunkId))
                .toList();
        return new AssetImportPlan(
                knowledgeBaseId,
                knowledgeBaseMetadata,
                true,
                documentRowsById.values().stream()
                        .sorted(java.util.Comparator.comparing(DocumentImportRow::documentId))
                        .toList(),
                chunkRows,
                List.copyOf(assetRows),
                List.copyOf(relations),
                Map.copyOf(chunkAssetMetadataByLogicalId)
        );
    }

    private static Map<MarkdownParserService.MarkdownSection, String> logicalChunkIds(
            String sourceDocumentId,
            List<MarkdownParserService.MarkdownSection> sections
    ) {
        Map<MarkdownParserService.MarkdownSection, String> ids = new IdentityHashMap<>();
        Map<String, Integer> indexesByPath = new LinkedHashMap<>();
        for (MarkdownParserService.MarkdownSection section : sections) {
            String path = normalizeContentPath(section.getContentPath());
            int index = indexesByPath.getOrDefault(path, 0);
            indexesByPath.put(path, index + 1);
            ids.put(section, sourceDocumentId + "#" + path + "#" + index);
        }
        return ids;
    }

    private static int identityIndexOf(
            List<MarkdownParserService.MarkdownSection> sections,
            MarkdownParserService.MarkdownSection target
    ) {
        for (int index = 0; index < sections.size(); index++) {
            if (sections.get(index) == target) {
                return index;
            }
        }
        throw new IllegalStateException("multimodal asset chunk section 不在 parser 输出中");
    }

    private static CandidateChunk chunkPayload(
            MarkdownParserService.MarkdownSection section,
            String sourceType,
            String sourceName,
            long documentSizeBytes,
            int sectionIndex,
            String assetId,
            String assetType,
            Map<String, Integer> locator
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(RagChunkSupport.buildChunkMetadata(
                section,
                sourceType,
                sourceName,
                sectionIndex
        ));
        metadata.put("asset", Map.of(
                "id", assetId,
                "type", assetType,
                "locator", new TreeMap<>(locator)
        ));
        return new CandidateChunk(
                sourceType,
                sourceName,
                documentSizeBytes,
                sectionIndex,
                section.getTitle(),
                section.getContentPath(),
                section.getContent() == null ? "" : section.getContent(),
                RagChunkSupport.buildChunkEmbeddingText(section),
                RagChunkSupport.buildRetrievableTitleSearchText(section, sourceName),
                Collections.unmodifiableMap(metadata)
        );
    }

    private static byte[] generatePdf(String pageText) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(pageText);
                contentStream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static Map<String, Integer> integerMap(JsonNode node) {
        Map<String, Integer> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asInt()));
        return Map.copyOf(values);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static byte[] readBytes(String path) throws IOException {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private static String resolveSiblingPath(String manifestPath, String relativePath) {
        int separator = manifestPath.lastIndexOf('/');
        return manifestPath.substring(0, separator + 1) + relativePath;
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String normalizeContentPath(String path) {
        return path == null ? "" : path.replaceAll("\\s*>\\s*", ">").trim();
    }

    private static String deterministicAssetUuid(
            String manifestSha256,
            String sourceDocumentId,
            String assetType,
            String assetKey
    ) {
        String value = String.join(":", "rag-eval", DATASET_ID, manifestSha256,
                "asset", sourceDocumentId, assetType, assetKey);
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String deterministicRuntimeUuid(String manifestSha256, String kind, String logicalId) {
        String value = String.join(":", "rag-eval", DATASET_ID, manifestSha256, kind, logicalId);
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    record CandidateProfile(
            String datasetId,
            String status,
            String manifestSha256,
            String caseSha256,
            String corpusSha256,
            List<CandidateCase> cases,
            List<CandidateAsset> assets,
            AssetImportPlan assetImportPlan
    ) {
        boolean releaseEligible() {
            return "frozen".equals(status) && cases.stream().allMatch(item -> "approved".equals(item.reviewStatus()));
        }
    }

    record CandidateCase(
            String caseId,
            String datasetId,
            String query,
            String queryType,
            List<String> kbScope,
            String expectedRoute,
            String sourceDocumentId,
            String sourceFile,
            String sourceGenerator,
            String sourceText,
            String sourceTextSha256,
            String goldChunkLogicalId,
            GoldAsset goldAsset,
            String goldEvidence,
            boolean shouldAbstain,
            String reviewStatus
    ) {
    }

    record GoldAsset(String assetType, String assetKey, Map<String, Integer> locator) {
    }

    record CandidateAsset(
            String sourceDocumentId,
            String runtimeDocumentUuid,
            String logicalChunkId,
            String runtimeChunkUuid,
            String runtimeAssetUuid,
            String assetType,
            String assetKey,
            Map<String, Integer> locator,
            String content,
            String contentHash,
            CandidateChunk chunkPayload
    ) {
    }

    record CandidateChunk(
            String sourceType,
            String sourceName,
            long documentSizeBytes,
            int sectionIndex,
            String title,
            String contentPath,
            String content,
            String embeddingText,
            String titleSearchText,
            Map<String, Object> metadata
    ) {
    }

    record AssetImportPlan(
            String knowledgeBaseId,
            Map<String, Object> knowledgeBaseMetadata,
            boolean candidateOnly,
            List<DocumentImportRow> documentRows,
            List<ChunkImportRow> chunkRows,
            List<AssetImportRow> assetRows,
            List<AssetChunkRelation> assetChunkRelations,
            Map<String, Map<String, Object>> chunkAssetMetadataByLogicalId
    ) {
    }

    record DocumentImportRow(
            String documentId,
            String knowledgeBaseId,
            String filename,
            String filetype,
            long sizeBytes,
            Map<String, Object> metadata
    ) {
    }

    record ChunkImportRow(
            String chunkId,
            String knowledgeBaseId,
            String documentId,
            String logicalChunkId,
            String content,
            Map<String, Object> metadata,
            String embeddingText,
            String titleSearchText
    ) {
    }

    record AssetImportRow(
            String assetId,
            String documentId,
            String assetType,
            String assetKey,
            Integer pageNumber,
            String locatorJson,
            String contentHash,
            String parserVersion,
            String status
    ) {
    }

    record AssetChunkRelation(
            String assetId,
            String chunkId,
            String assetDocumentId,
            String chunkDocumentId
    ) {
    }
}
