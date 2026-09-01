package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.MarkdownParserService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownParserServiceImplTest {

    @Test
    void shouldExtractPdfTextWithPageMetadata() throws Exception {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();

        List<MarkdownParserService.MarkdownSection> sections = service.parsePdf(
                new ByteArrayInputStream(twoPagePdf())
        );

        assertEquals(2, sections.size());
        assertEquals(1, sections.get(0).getPageNumber());
        assertEquals(2, sections.get(1).getPageNumber());
        assertTrue(sections.get(0).getContent().contains("Page one"));
    }

    @Test
    void shouldSplitLongPdfPageAndKeepPageMetadata() throws Exception {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();
        String firstParagraph = "first " + "a".repeat(1200) + ".";
        String secondParagraph = "second " + "b".repeat(1200);

        List<MarkdownParserService.MarkdownSection> sections = service.parsePdf(
                new ByteArrayInputStream(pdfWithPages(List.of(firstParagraph + secondParagraph)))
        );

        assertEquals(2, sections.size());
        assertEquals(1, sections.get(0).getPageNumber());
        assertEquals(1, sections.get(1).getPageNumber());
        assertTrue(sections.stream().allMatch(section -> section.getContent().length() <= 2000));
    }

    private byte[] twoPagePdf() throws Exception {
        return pdfWithPages(List.of("Page one", "Page two"));
    }

    private byte[] pdfWithPages(List<String> pageContents) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String text : pageContents) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 12);
                    contentStream.newLineAtOffset(72, 720);
                    contentStream.showText(text);
                    contentStream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    @Test
    void shouldRejectCorruptPdf() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.parsePdf(new ByteArrayInputStream(new byte[]{1, 2, 3}))
        );
    }

    @Test
    void shouldExtractMarkdownTableWithStableLineLocator() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();
        String markdown = """
                # 发布配置
                | 指标 | 值 |
                | --- | --- |
                | 超时 | 30s |
                """;

        List<MarkdownParserService.MarkdownTable> tables = service.parseMarkdownTables(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(1, tables.size());
        assertEquals("| 指标 | 值 |\n| --- | --- |\n| 超时 | 30s |", tables.get(0).content());
        assertEquals(2, tables.get(0).startLine());
        assertEquals(4, tables.get(0).endLine());
    }

    @Test
    void shouldExtractMultiLevelHeadingStructure() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();
        String markdown = """
                # Java
                概览

                ## 并发
                并发概览

                ### 回答
                这是叶子回答

                ## JVM
                JVM 概览
                """;

        List<MarkdownParserService.MarkdownSection> sections = service.parseMarkdown(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(4, sections.size());

        MarkdownParserService.MarkdownSection root = sections.get(0);
        assertEquals("Java", root.getTitle());
        assertEquals("Java", root.getContentPath());
        assertNull(root.getParentContentPath());
        assertEquals(1, root.getHeadingLevel());
        assertTrue(root.isHasChildren());
        assertEquals(MarkdownParserService.SectionType.PARENT_OVERVIEW, root.getSectionType());
        assertEquals(1, root.getPathDepth());

        MarkdownParserService.MarkdownSection chapter = sections.get(1);
        assertEquals("并发", chapter.getTitle());
        assertEquals("Java > 并发", chapter.getContentPath());
        assertEquals("Java", chapter.getParentContentPath());
        assertEquals(2, chapter.getHeadingLevel());
        assertTrue(chapter.isHasChildren());
        assertEquals(MarkdownParserService.SectionType.PARENT_OVERVIEW, chapter.getSectionType());
        assertEquals(2, chapter.getPathDepth());

        MarkdownParserService.MarkdownSection leaf = sections.get(2);
        assertEquals("回答", leaf.getTitle());
        assertEquals("Java > 并发 > 回答", leaf.getContentPath());
        assertEquals("Java > 并发", leaf.getParentContentPath());
        assertEquals(3, leaf.getHeadingLevel());
        assertFalse(leaf.isHasChildren());
        assertEquals(MarkdownParserService.SectionType.LEAF_QA, leaf.getSectionType());
        assertEquals(3, leaf.getPathDepth());
        assertTrue(leaf.getLocalContentLength() > 0);

        MarkdownParserService.MarkdownSection sibling = sections.get(3);
        assertEquals("JVM", sibling.getTitle());
        assertEquals("Java > JVM", sibling.getContentPath());
        assertEquals("Java", sibling.getParentContentPath());
        assertFalse(sibling.isHasChildren());
        assertEquals(MarkdownParserService.SectionType.LEAF_CONTENT, sibling.getSectionType());
    }

    @Test
    void shouldNotChainSiblingQuestionsAcrossSkippedHeadingLevels() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();
        String markdown = """
                # 项目
                ## 认证
                ### 1. 第一个问题？
                #### 回答
                A1
                ### 2. 第二个问题？
                #### 回答
                A2
                """;

        List<MarkdownParserService.MarkdownSection> sections = service.parseMarkdown(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(6, sections.size());
        assertEquals("项目 > 认证 > 1. 第一个问题？", sections.get(2).getContentPath());
        assertEquals("项目 > 认证 > 1. 第一个问题？", sections.get(3).getParentContentPath());
        assertEquals("项目 > 认证 > 2. 第二个问题？", sections.get(4).getContentPath());
        assertEquals("项目 > 认证 > 2. 第二个问题？", sections.get(5).getParentContentPath());
    }

    @Test
    void shouldKeepMarkdownPreambleAsDedicatedSection() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();
        String markdown = """
                这是一段没有标题的文档前言。

                # 部署
                部署正文
                """;

        List<MarkdownParserService.MarkdownSection> sections = service.parseMarkdown(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(2, sections.size());
        assertEquals("文档前言", sections.get(0).getTitle());
        assertEquals("这是一段没有标题的文档前言。", sections.get(0).getContent());
        assertEquals("部署", sections.get(1).getTitle());
    }

    @Test
    void shouldCreateSectionForPlainMarkdownWithoutHeadings() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();
        String markdown = "第一段正文。\n\n第二段正文。";

        List<MarkdownParserService.MarkdownSection> sections = service.parseMarkdown(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(1, sections.size());
        assertEquals("文档", sections.get(0).getTitle());
        assertEquals("第一段正文。\n第二段正文。", sections.get(0).getContent());
    }

    @Test
    void shouldCleanHtmlWithoutHeadingsIntoTextSection() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();
        String html = """
                <html><head><style>.hidden { display: none; }</style></head><body>
                <script>window.noise = true;</script><p>第一段正文。</p><p>第二段正文。</p>
                </body></html>
                """;

        List<MarkdownParserService.MarkdownSection> sections = service.parseHtml(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(1, sections.size());
        assertEquals("文档", sections.get(0).getTitle());
        assertEquals("第一段正文。\n第二段正文。", sections.get(0).getContent());
        assertFalse(sections.get(0).getContent().contains("window.noise"));
        assertFalse(sections.get(0).getContent().contains("display: none"));
    }

    @Test
    void shouldSplitLongSectionAtParagraphBoundaries() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();
        String firstParagraph = "第一段" + "a".repeat(1200);
        String secondParagraph = "第二段" + "b".repeat(1200);
        String markdown = "# 部署\n" + firstParagraph + "\n\n" + secondParagraph;

        List<MarkdownParserService.MarkdownSection> sections = service.parseMarkdown(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(2, sections.size());
        assertEquals("部署", sections.get(0).getContentPath());
        assertEquals("部署", sections.get(1).getContentPath());
        assertEquals(firstParagraph, sections.get(0).getContent());
        assertEquals(secondParagraph, sections.get(1).getContent());
        assertTrue(sections.stream().allMatch(section -> section.getContent().length() <= 2000));
    }

    @Test
    void shouldApplyConfiguredOverlapWhenSplittingLongSection() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl(1000, 100);
        String markdown = "# 长文\n" + "x".repeat(3000);

        List<MarkdownParserService.MarkdownSection> sections = service.parseMarkdown(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(4, sections.size());
        assertTrue(sections.stream().allMatch(section -> section.getContent().length() <= 1000));
        assertEquals(
                sections.get(0).getContent().substring(900),
                sections.get(1).getContent().substring(0, 100)
        );
        assertEquals(3000, sections.stream().mapToInt(section -> section.getContent().length()).sum() - 300);
    }

    @Test
    void shouldKeepOneStructuredSectionWhenLengthLimitIsDisabled() {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl(0, 0);
        String markdown = "# 长文\n" + "x".repeat(3000);

        List<MarkdownParserService.MarkdownSection> sections = service.parseMarkdown(
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(1, sections.size());
        assertEquals(3000, sections.get(0).getContent().length());
    }

    @Test
    void shouldNotKeepRequestMarkdownInSingletonState() {
        for (Field field : MarkdownParserServiceImpl.class.getDeclaredFields()) {
            assertFalse(field.getName().equals("originalMarkdownContent"));
        }
    }
}
