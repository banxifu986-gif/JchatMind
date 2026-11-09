package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.ingestion.PdfImageExtractor;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.impl.MarkdownParserServiceImpl;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HexFormat;
import java.util.Set;

/**
 * dmi-image-rag-v1 candidate fixture 加载器：manifest/case/corpus 哈希校验、
 * PDF 内嵌图片运行时生成与提取、gold asset 定位与 G3 hint 路由校验。
 */
final class DmiImageRagCandidateFixtureLoader {

    static final String DATASET_ID = "dmi-image-rag-v1";
    private static final String SOURCE_GENERATOR = "pdfbox-embedded-image-v1";
    private static final java.util.Set<String> IMAGE_KINDS = java.util.Set.of(
            "TEXT_IMAGE", "TABLE_IMAGE", "DIAGRAM", "SCREENSHOT", "CHART",
            "ARCHITECTURE", "DEP_TABLE"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DmiImageRagCandidateFixtureLoader() {
    }

    static DmiCandidateProfile load(String manifestPath) throws IOException {
        byte[] manifestBytes = readBytes(manifestPath);
        JsonNode manifest = OBJECT_MAPPER.readTree(manifestBytes);
        validateManifest(manifest);
        String manifestSha256 = sha256(manifestBytes);

        byte[] caseBytes = readBytes(resolveSiblingPath(manifestPath, manifest.path("caseFile").asText()));
        if (!sha256(caseBytes).equals(manifest.path("caseSha256").asText())) {
            throw new IllegalStateException("dmi candidate case SHA-256 不匹配");
        }
        List<DmiCandidateCase> cases = parseCases(caseBytes);

        List<String> corpusFiles = strings(manifest.path("corpusFiles")).stream().sorted().toList();
        StringBuilder corpusFingerprint = new StringBuilder();
        for (String relativePath : corpusFiles) {
            String fileName = fileName(relativePath);
            corpusFingerprint.append(fileName).append('\n')
                    .append(new String(readBytes(resolveSiblingPath(manifestPath, relativePath)), StandardCharsets.UTF_8))
                    .append('\n');
        }
        if (!sha256(corpusFingerprint.toString().getBytes(StandardCharsets.UTF_8))
                .equals(manifest.path("corpusSha256").asText())) {
            throw new IllegalStateException("dmi candidate corpus SHA-256 不匹配");
        }

        validateRoutes(cases);
        List<DmiCandidateAsset> assets = buildAssets(cases);
        validateGoldCases(cases, assets);
        return new DmiCandidateProfile(manifestSha256, List.copyOf(cases), List.copyOf(assets));
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
            throw new IllegalStateException("dmi candidate manifest 不符合 schema v1");
        }
    }

    private static List<DmiCandidateCase> parseCases(byte[] caseBytes) throws IOException {
        List<DmiCandidateCase> cases = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        for (String line : new String(caseBytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = OBJECT_MAPPER.readTree(line);
            String sourceGenerator = nullableText(node.path("sourceGenerator"));
            if (StringUtils.hasText(sourceGenerator) && !SOURCE_GENERATOR.equals(sourceGenerator)) {
                throw new IllegalStateException("dmi candidate 不支持的 sourceGenerator: " + sourceGenerator);
            }
            if (StringUtils.hasText(sourceGenerator)
                    && (!StringUtils.hasText(nullableText(node.path("sourceText")))
                    || !sha256(nullableText(node.path("sourceText")).getBytes(StandardCharsets.UTF_8))
                    .equals(nullableText(node.path("sourceTextSha256")))
                    || !StringUtils.hasText(nullableText(node.path("pageText")))
                    || !sha256(nullableText(node.path("pageText")).getBytes(StandardCharsets.UTF_8))
                    .equals(nullableText(node.path("pageTextSha256"))))) {
                throw new IllegalStateException("dmi candidate 图片/页面文本或哈希不匹配: "
                        + node.path("caseId").asText());
            }
            DmiGoldAsset goldAsset = node.path("goldAsset").isNull() || node.path("goldAsset").isMissingNode()
                    ? null
                    : new DmiGoldAsset(
                    node.path("goldAsset").path("assetType").asText(),
                    node.path("goldAsset").path("assetKey").asText(),
                    node.path("goldAsset").path("locator").path("pageNumber").asInt(),
                    doubleList(node.path("goldAsset").path("locator").path("bbox"))
            );
            String goldChunkLogicalId = nullableText(node.path("goldChunkLogicalId"));
            DmiCandidateCase candidateCase = new DmiCandidateCase(
                    node.path("caseId").asText(),
                    node.path("datasetId").asText(),
                    node.path("query").asText(),
                    node.path("queryType").asText(),
                    strings(node.path("kbScope")),
                    node.path("expectedRoute").asText(),
                    node.path("sourceDocumentId").asText(),
                    StringUtils.hasText(node.path("imageKind").asText()) ? node.path("imageKind").asText() : "TEXT_IMAGE",
                    strings(node.path("goldEntityNames")),
                    parseGoldRelations(node.path("goldRelations")),
                    sourceGenerator,
                    nullableText(node.path("sourceText")),
                    nullableText(node.path("pageText")),
                    goldChunkLogicalId,
                    goldAsset,
                    nullableText(node.path("goldEvidence")),
                    node.path("shouldAbstain").asBoolean(),
                    node.path("reviewStatus").asText()
            );
            if (!DATASET_ID.equals(candidateCase.datasetId())
                    || !StringUtils.hasText(candidateCase.caseId())
                    || !StringUtils.hasText(candidateCase.query())
                    || candidateCase.kbScope().isEmpty()
                    || !StringUtils.hasText(candidateCase.expectedRoute())
                    || !StringUtils.hasText(candidateCase.sourceDocumentId())
                    || !IMAGE_KINDS.contains(candidateCase.imageKind())
                    || !("pending".equals(candidateCase.reviewStatus())
                    || "approved".equals(candidateCase.reviewStatus()))) {
                throw new IllegalStateException("dmi candidate case 缺少字段、数据集不匹配或 reviewStatus 非法: caseId=" + candidateCase.caseId() + " datasetId=" + candidateCase.datasetId() + " imageKind=" + candidateCase.imageKind() + " reviewStatus=" + candidateCase.reviewStatus());
            }
            if (!caseIds.add(candidateCase.caseId())) {
                throw new IllegalStateException("dmi candidate caseId 重复: " + candidateCase.caseId());
            }
            cases.add(candidateCase);
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("dmi candidate case 为空");
        }
        return List.copyOf(cases);
    }

    private static void validateRoutes(List<DmiCandidateCase> cases) {
        RagRouter router = new RagRouter();
        for (DmiCandidateCase candidateCase : cases) {
            String actualRoute;
            if ("no_visual_asset".equals(candidateCase.queryType())) {
                actualRoute = router.decide(
                        candidateCase.query(),
                        candidateCase.kbScope(),
                        true,
                        false,
                        true,
                        new RagRouter.VisualEvidenceHint(true, false)
                ).route().name();
            } else {
                actualRoute = router.decide(
                        candidateCase.query(),
                        candidateCase.kbScope(),
                        true,
                        false,
                        true
                ).route().name();
            }
            if (!actualRoute.equals(candidateCase.expectedRoute())) {
                throw new IllegalStateException("dmi candidate Router route 不匹配: "
                        + candidateCase.caseId() + " expected=" + candidateCase.expectedRoute()
                        + " actual=" + actualRoute);
            }
        }
    }

    private static List<DmiCandidateAsset> buildAssets(List<DmiCandidateCase> cases) throws IOException {
        List<DmiCandidateAsset> assets = new ArrayList<>();
        for (DmiCandidateCase candidateCase : cases) {
            if (!StringUtils.hasText(candidateCase.sourceGenerator())) {
                continue;
            }
            byte[] pdfBytes = generatePdf(candidateCase.pageText(), candidateCase.sourceText(), candidateCase.imageKind());
            MarkdownParserService parser = new MarkdownParserServiceImpl();
            List<MarkdownParserService.MarkdownSection> pages =
                    parser.parsePdf(new ByteArrayInputStream(pdfBytes));
            List<PdfImageExtractor.PdfEmbeddedImage> images =
                    new PdfImageExtractor().extract(new ByteArrayInputStream(pdfBytes));
            if (pages.size() != 1 || images.size() != 1) {
                throw new IllegalStateException("dmi candidate PDF fixture 必须恰好一页一图: "
                        + candidateCase.caseId());
            }
            MarkdownParserService.MarkdownSection page = pages.get(0);
            int pageNumber = page.getPageNumber();
            PdfImageExtractor.PdfEmbeddedImage image = images.get(0);
            if (image.pageNumber() != pageNumber) {
                throw new IllegalStateException("dmi candidate 图片页码与页面文本不一致: "
                        + candidateCase.caseId());
            }
            String logicalChunkId = candidateCase.sourceDocumentId() + "#"
                    + normalizeContentPath(page.getContentPath()) + "#0";
            List<Double> bbox = new ArrayList<>();
            for (float value : image.bbox()) {
                bbox.add((double) value);
            }
            assets.add(new DmiCandidateAsset(
                    candidateCase.sourceDocumentId(),
                    logicalChunkId,
                    "IMAGE",
                    "image-1",
                    pageNumber,
                    bbox,
                    candidateCase.sourceText(),
                    sha256(candidateCase.sourceText().getBytes(StandardCharsets.UTF_8))
            ));
        }
        return List.copyOf(assets);
    }

    private static void validateGoldCases(List<DmiCandidateCase> cases, List<DmiCandidateAsset> assets) {
        for (DmiCandidateCase candidateCase : cases) {
            if (candidateCase.shouldAbstain()) {
                if (candidateCase.goldAsset() != null
                        || StringUtils.hasText(candidateCase.goldChunkLogicalId())
                        || StringUtils.hasText(candidateCase.goldEvidence())) {
                    throw new IllegalStateException("dmi candidate 拒答 case 不能声明 gold evidence: "
                            + candidateCase.caseId());
                }
                continue;
            }
            if (candidateCase.goldAsset() == null
                    || !StringUtils.hasText(candidateCase.goldChunkLogicalId())
                    || !StringUtils.hasText(candidateCase.goldEvidence())) {
                throw new IllegalStateException("dmi candidate 可回答 case 缺少 asset/chunk/evidence gold: "
                        + candidateCase.caseId());
            }
            DmiCandidateAsset asset = assets.stream()
                    .filter(item -> item.sourceDocumentId().equals(candidateCase.sourceDocumentId()))
                    .filter(item -> item.assetType().equals(candidateCase.goldAsset().assetType()))
                    .filter(item -> item.assetKey().equals(candidateCase.goldAsset().assetKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("dmi candidate case gold asset 未映射: "
                            + candidateCase.caseId()));
            if (asset.pageNumber() != candidateCase.goldAsset().pageNumber()
                    || asset.bbox().size() != 4
                    || !asset.content().contains(candidateCase.goldEvidence())) {
                throw new IllegalStateException("dmi candidate case gold 定位或证据与资产不匹配: "
                        + candidateCase.caseId());
            }
        }
    }

    static byte[] generatePdf(String pageText, String imageText, String imageKind) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            int imgW = "ARCHITECTURE".equals(imageKind) || "DEP_TABLE".equals(imageKind) ? 640 : 480;
            int imgH = "ARCHITECTURE".equals(imageKind) || "DEP_TABLE".equals(imageKind) ? 360 : 160;
            BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, imgW, imgH);
            graphics.setColor(Color.BLACK);
            graphics.setFont(graphics.getFont().deriveFont(20F));
            renderImageContent(graphics, imageText, imageKind);
            graphics.dispose();
            PDImageXObject imageXObject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(pageText);
                contentStream.endText();
                contentStream.drawImage(imageXObject, 50, 280, imgW - 80, imgH - 60);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    static void renderImageContent(Graphics2D graphics, String imageText, String imageKind) {
        if ("ARCHITECTURE".equals(imageKind)) {
            renderArchitectureDiagram(graphics, imageText);
            return;
        }
        if ("DEP_TABLE".equals(imageKind)) {
            renderDependencyTable(graphics, imageText);
            return;
        }
        graphics.setFont(graphics.getFont().deriveFont(20F));
        if ("TABLE_IMAGE".equals(imageKind)) {
            String[] rows = imageText.split("\\R");
            int y = 30;
            int[] columnWidths = {220, 220};
            for (int row = 0; row < rows.length; row++) {
                String[] cells = rows[row].split("\\|");
                int x = 20;
                for (int cell = 0; cell < cells.length && cell < 2; cell++) {
                    graphics.drawRect(x, y, columnWidths[cell], 36);
                    graphics.drawString(cells[cell].trim(), x + 8, y + 25);
                    x += columnWidths[cell];
                }
                y += 36;
            }
            return;
        }
        if ("DIAGRAM".equals(imageKind)) {
            String[] parts = imageText.split(" -> ");
            int boxWidth = 180;
            for (int i = 0; i < parts.length; i++) {
                int x = 20 + i * (boxWidth + 40);
                graphics.drawRect(x, 50, boxWidth, 60);
                graphics.drawString(parts[i].trim(), x + 10, 85);
                if (i < parts.length - 1) {
                    graphics.drawLine(x + boxWidth, 80, x + boxWidth + 40, 80);
                }
            }
            return;
        }
        graphics.drawString(imageText, 24, 80);
    }

    /** 渲染多服务架构图：多矩形框 + 中文标签 + 箭头。imageText 格式: "A->B;B->C;C->D" */
    static void renderArchitectureDiagram(Graphics2D g, String imageText) {
        g.setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 14));
        String[] flows = imageText.split(";");
        java.util.LinkedHashMap<String, java.awt.Point> boxes = new java.util.LinkedHashMap<>();
        java.util.List<String[]> edges = new ArrayList<>();
        int boxW = 140;
        int boxH = 50;
        int margin = 30;
        int spacing = 40;
        int y = 40;
        for (String flow : flows) {
            String[] parts = flow.split("->");
            for (String part : parts) {
                String name = part.trim();
                if (!boxes.containsKey(name)) {
                    int x = margin + boxes.size() * (boxW + spacing);
                    if (x + boxW > 620) {
                        x = margin;
                        y += boxH + 50;
                    }
                    boxes.put(name, new java.awt.Point(x, y));
                }
            }
            if (parts.length >= 2) {
                edges.add(new String[]{parts[0].trim(), parts[1].trim()});
            }
        }
        for (var entry : boxes.entrySet()) {
            g.setColor(new Color(230, 240, 255));
            g.fillRect(entry.getValue().x, entry.getValue().y, boxW, boxH);
            g.setColor(Color.BLACK);
            g.drawRect(entry.getValue().x, entry.getValue().y, boxW, boxH);
            var fm = g.getFontMetrics();
            int textX = entry.getValue().x + (boxW - fm.stringWidth(entry.getKey())) / 2;
            int textY = entry.getValue().y + boxH / 2 + fm.getDescent();
            g.drawString(entry.getKey(), textX, textY);
        }
        g.setColor(new Color(60, 60, 200));
        for (String[] edge : edges) {
            var from = boxes.get(edge[0]);
            var to = boxes.get(edge[1]);
            if (from == null || to == null) { continue; }
            int x1 = from.x + boxW;
            int y1 = from.y + boxH / 2;
            int x2 = to.x;
            int y2 = to.y + boxH / 2;
            g.drawLine(x1, y1, x2, y2);
            g.fillPolygon(new int[]{x2, x2 - 10, x2 - 10}, new int[]{y2, y2 - 5, y2 + 5}, 3);
        }
    }

    /** 渲染中文依赖表：imageText 格式: "表头1|表头2|表头3\n行1列1|行1列2|行1列3\n..." */
    static void renderDependencyTable(Graphics2D g, String tableText) {
        g.setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 13));
        String[] rows = tableText.split("\\R");
        int[] columnWidths = {160, 160, 120, 100};
        int x0 = 20;
        int y0 = 20;
        int rowHeight = 32;
        for (int row = 0; row < rows.length; row++) {
            String[] cells = rows[row].split("\\|");
            int x = x0;
            for (int col = 0; col < cells.length && col < columnWidths.length; col++) {
                if (row == 0) {
                    g.setColor(new Color(220, 230, 250));
                    g.fillRect(x, y0 + row * rowHeight, columnWidths[col], rowHeight);
                }
                g.setColor(Color.BLACK);
                g.drawRect(x, y0 + row * rowHeight, columnWidths[col], rowHeight);
                g.drawString(cells[col].trim(), x + 6, y0 + row * rowHeight + 22);
                x += columnWidths[col];
            }
        }
    }

    private static String normalizeContentPath(String contentPath) {
        return contentPath == null ? "" : contentPath.trim();
    }

    private static String resolveSiblingPath(String manifestPath, String relativePath) {
        int separator = manifestPath.lastIndexOf('/');
        return manifestPath.substring(0, separator + 1) + relativePath;
    }

    private static String fileName(String relativePath) {
        return relativePath.substring(relativePath.lastIndexOf('/') + 1);
    }

    private static List<Double> doubleList(JsonNode node) {
        List<Double> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asDouble()));
        return List.copyOf(values);
    }

    private static List<DmiGoldRelation> parseGoldRelations(JsonNode array) {
        List<DmiGoldRelation> relations = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                relations.add(new DmiGoldRelation(
                        item.path("subject").asText(),
                        item.path("predicate").asText(),
                        item.path("object").asText()));
            }
        }
        return List.copyOf(relations);
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    record DmiGoldRelation(String subject, String predicate, String object) {
    }

    record DmiGoldAsset(String assetType, String assetKey, int pageNumber, List<Double> bbox) {
    }

    record DmiCandidateCase(
            String caseId,
            String datasetId,
            String query,
            String queryType,
            List<String> kbScope,
            String expectedRoute,
            String sourceDocumentId,
            String imageKind,
            List<String> goldEntityNames,
            List<DmiGoldRelation> goldRelations,
            String sourceGenerator,
            String sourceText,
            String pageText,
            String goldChunkLogicalId,
            DmiGoldAsset goldAsset,
            String goldEvidence,
            boolean shouldAbstain,
            String reviewStatus
    ) {
    }

    record DmiCandidateAsset(
            String sourceDocumentId,
            String logicalChunkId,
            String assetType,
            String assetKey,
            int pageNumber,
            List<Double> bbox,
            String content,
            String contentHash
    ) {
    }

    record DmiCandidateProfile(
            String manifestSha256,
            List<DmiCandidateCase> cases,
            List<DmiCandidateAsset> assets
    ) {
    }
}
