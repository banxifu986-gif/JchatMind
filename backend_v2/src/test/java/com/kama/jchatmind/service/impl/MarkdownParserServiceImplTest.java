package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.MarkdownParserService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownParserServiceImplTest {

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
}
