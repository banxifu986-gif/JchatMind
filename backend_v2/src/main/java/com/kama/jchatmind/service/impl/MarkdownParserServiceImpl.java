package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.MarkdownParserService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class MarkdownParserServiceImpl implements MarkdownParserService {

    private static final int MAX_SECTION_CONTENT_LENGTH = 2000;
    private static final int MIN_PREFERRED_CHUNK_LENGTH = 1000;
    private static final String DOCUMENT_TITLE = "文档";
    private static final String PREAMBLE_TITLE = "文档前言";
    private static final Pattern HTML_HEADING_PATTERN = Pattern.compile(
            "(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>"
    );

    private final Parser parser;

    public MarkdownParserServiceImpl() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    @Override
    public List<MarkdownSection> parseMarkdown(InputStream inputStream) {
        try {
            String markdown = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Document document = parser.parse(markdown);

            List<MarkdownSection> sections = new ArrayList<>();
            extractSections(document, sections, markdown);
            if (sections.isEmpty()) {
                sections.add(createStandaloneSection(DOCUMENT_TITLE, extractDocumentContent(document, markdown)));
            }
            sections = splitOversizedSections(sections);

            log.info("解析 Markdown 完成，共提取 {} 个章节", sections.size());
            return sections;
        } catch (Exception e) {
            log.error("解析 Markdown 失败", e);
            throw new RuntimeException("解析 Markdown 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<MarkdownTable> parseMarkdownTables(InputStream inputStream) {
        try {
            String markdown = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Document document = parser.parse(markdown);
            List<MarkdownTable> tables = new ArrayList<>();
            collectMarkdownTables(document, markdown, tables);
            return tables;
        } catch (IOException e) {
            throw new RuntimeException("读取 Markdown 表格失败", e);
        }
    }

    @Override
    public List<MarkdownSection> parseHtml(InputStream inputStream) {
        try {
            String html = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = HTML_HEADING_PATTERN.matcher(html);
            List<HtmlHeading> headings = new ArrayList<>();
            while (matcher.find()) {
                headings.add(new HtmlHeading(
                        Integer.parseInt(matcher.group(1)),
                        stripHtml(matcher.group(2)).replace("\n", " ").trim(),
                        matcher.start(),
                        matcher.end()
                ));
            }
            List<MarkdownSection> sections = new ArrayList<>();
            if (headings.isEmpty()) {
                sections.add(createStandaloneSection(DOCUMENT_TITLE, stripHtml(html)));
                return splitOversizedSections(sections);
            }

            String preamble = stripHtml(html.substring(0, headings.get(0).start()));
            if (!preamble.isBlank()) {
                sections.add(createStandaloneSection(PREAMBLE_TITLE, preamble));
            }
            List<String> currentPathTitles = new ArrayList<>();
            List<Integer> currentPathLevels = new ArrayList<>();
            for (int i = 0; i < headings.size(); i++) {
                HtmlHeading heading = headings.get(i);
                if (heading.title().isEmpty()) {
                    continue;
                }
                while (!currentPathLevels.isEmpty()
                        && currentPathLevels.get(currentPathLevels.size() - 1) >= heading.level()) {
                    currentPathLevels.remove(currentPathLevels.size() - 1);
                    currentPathTitles.remove(currentPathTitles.size() - 1);
                }
                String parentContentPath = currentPathTitles.isEmpty()
                        ? null
                        : String.join(" > ", currentPathTitles);
                String contentPath = parentContentPath == null
                        ? heading.title()
                        : parentContentPath + " > " + heading.title();
                int contentEnd = i + 1 < headings.size() ? headings.get(i + 1).start() : html.length();
                String content = stripHtml(html.substring(heading.end(), contentEnd));
                boolean hasChildren = i + 1 < headings.size()
                        && headings.get(i + 1).level() > heading.level();
                sections.add(new MarkdownSection(
                        heading.title(),
                        content,
                        contentPath,
                        parentContentPath,
                        heading.level(),
                        hasChildren,
                        resolveSectionType(heading.title(), hasChildren),
                        pathDepth(contentPath),
                        content.length()
                ));
                currentPathLevels.add(heading.level());
                currentPathTitles.add(heading.title());
            }
            sections = splitOversizedSections(sections);
            log.info("解析 HTML 完成，共提取 {} 个章节", sections.size());
            return sections;
        } catch (Exception e) {
            log.error("解析 HTML 失败", e);
            throw new RuntimeException("解析 HTML 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<MarkdownSection> parsePdf(InputStream inputStream) {
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<MarkdownSection> sections = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String content = stripper.getText(document).trim();
                if (content.isEmpty()) {
                    continue;
                }
                String title = "第 " + page + " 页";
                sections.add(new MarkdownSection(
                        title,
                        content,
                        title,
                        null,
                        1,
                        false,
                        SectionType.LEAF_CONTENT,
                        1,
                        content.length(),
                        page
                ));
            }
            if (sections.isEmpty()) {
                throw new IllegalArgumentException("PDF 未提取到文本");
            }
            sections = splitOversizedSections(sections);
            log.info("解析 PDF 完成，共提取 {} 页文本", sections.size());
            return sections;
        } catch (IOException | RuntimeException e) {
            log.error("解析 PDF 失败", e);
            if (e instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("解析 PDF 失败", e);
        }
    }

    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<(script|style|noscript|template|head)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?(?:p|div|li|ul|ol|table|tr|h[1-6]|section|article|blockquote)\\b[^>]*>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n+", "\n")
                .trim();
    }

    private record HtmlHeading(int level, String title, int start, int end) {
    }

    private void extractSections(
            Document document,
            List<MarkdownSection> sections,
            String markdown
    ) {
        List<Node> topLevelNodes = new ArrayList<>();
        Node child = document.getFirstChild();
        while (child != null) {
            topLevelNodes.add(child);
            child = child.getNext();
        }

        List<String> currentPathTitles = new ArrayList<>();
        List<Integer> currentPathLevels = new ArrayList<>();

        int firstHeadingIndex = firstHeadingIndex(topLevelNodes);
        if (firstHeadingIndex > 0) {
            String preamble = extractDocumentContent(topLevelNodes, 0, firstHeadingIndex, markdown);
            if (!preamble.isBlank()) {
                sections.add(createStandaloneSection(PREAMBLE_TITLE, preamble));
            }
        }

        for (int i = 0; i < topLevelNodes.size(); i++) {
            Node node = topLevelNodes.get(i);
            if (!(node instanceof Heading heading)) {
                continue;
            }

            String title = extractHeadingText(heading);
            if (title == null || title.trim().isEmpty()) {
                continue;
            }

            while (!currentPathLevels.isEmpty()
                    && currentPathLevels.get(currentPathLevels.size() - 1) >= heading.getLevel()) {
                currentPathLevels.remove(currentPathLevels.size() - 1);
                currentPathTitles.remove(currentPathTitles.size() - 1);
            }

            String normalizedTitle = title.trim();
            String parentContentPath = currentPathTitles.isEmpty()
                    ? null
                    : String.join(" > ", currentPathTitles);
            String contentPath = parentContentPath == null
                    ? normalizedTitle
                    : parentContentPath + " > " + normalizedTitle;

            StringBuilder contentBuilder = new StringBuilder();
            for (int j = i + 1; j < topLevelNodes.size(); j++) {
                Node nextNode = topLevelNodes.get(j);
                if (nextNode instanceof Heading) {
                    break;
                }

                String content = extractNodeContent(nextNode, markdown);
                if (content != null && !content.trim().isEmpty()) {
                    if (contentBuilder.length() > 0) {
                        contentBuilder.append("\n");
                    }
                    contentBuilder.append(content);
                }
            }

            String content = contentBuilder.toString().trim();
            boolean hasChildren = hasChildHeading(topLevelNodes, i, heading.getLevel());
            sections.add(new MarkdownSection(
                    normalizedTitle,
                    content,
                    contentPath,
                    parentContentPath,
                    heading.getLevel(),
                    hasChildren,
                    resolveSectionType(normalizedTitle, hasChildren),
                    pathDepth(contentPath),
                    content.length()
            ));

            currentPathLevels.add(heading.getLevel());
            currentPathTitles.add(normalizedTitle);
        }
    }

    private int firstHeadingIndex(List<Node> nodes) {
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index) instanceof Heading) {
                return index;
            }
        }
        return -1;
    }

    private String extractDocumentContent(Document document, String markdown) {
        List<Node> nodes = new ArrayList<>();
        Node child = document.getFirstChild();
        while (child != null) {
            nodes.add(child);
            child = child.getNext();
        }
        return extractDocumentContent(nodes, 0, nodes.size(), markdown);
    }

    private String extractDocumentContent(List<Node> nodes, int startIndex, int endIndex, String markdown) {
        StringBuilder content = new StringBuilder();
        for (int index = startIndex; index < endIndex; index++) {
            String nodeContent = extractNodeContent(nodes.get(index), markdown);
            if (nodeContent == null || nodeContent.trim().isEmpty()) {
                continue;
            }
            if (content.length() > 0) {
                content.append("\n");
            }
            content.append(nodeContent);
        }
        return content.toString().trim();
    }

    private MarkdownSection createStandaloneSection(String title, String content) {
        String normalizedContent = content == null ? "" : content.trim();
        return new MarkdownSection(
                title,
                normalizedContent,
                title,
                null,
                1,
                false,
                SectionType.LEAF_CONTENT,
                1,
                normalizedContent.length()
        );
    }

    private List<MarkdownSection> splitOversizedSections(List<MarkdownSection> sections) {
        List<MarkdownSection> splitSections = new ArrayList<>();
        for (MarkdownSection section : sections) {
            String content = section.getContent();
            if (content == null || content.length() <= MAX_SECTION_CONTENT_LENGTH) {
                splitSections.add(section);
                continue;
            }
            for (String chunkContent : splitContent(content)) {
                splitSections.add(new MarkdownSection(
                        section.getTitle(),
                        chunkContent,
                        section.getContentPath(),
                        section.getParentContentPath(),
                        section.getHeadingLevel(),
                        section.isHasChildren(),
                        section.getSectionType(),
                        section.getPathDepth(),
                        chunkContent.length(),
                        section.getPageNumber()
                ));
            }
        }
        return splitSections;
    }

    private List<String> splitContent(String content) {
        List<String> chunks = new ArrayList<>();
        String normalizedContent = content.trim();
        int start = 0;
        while (start < normalizedContent.length()) {
            int end = findChunkEnd(normalizedContent, start);
            String chunk = normalizedContent.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start = end;
            while (start < normalizedContent.length() && Character.isWhitespace(normalizedContent.charAt(start))) {
                start++;
            }
        }
        return chunks;
    }

    private int findChunkEnd(String content, int start) {
        int limit = Math.min(start + MAX_SECTION_CONTENT_LENGTH, content.length());
        if (limit == content.length()) {
            return limit;
        }
        int minimumPreferredEnd = Math.min(start + MIN_PREFERRED_CHUNK_LENGTH, limit);
        for (int index = limit - 1; index >= minimumPreferredEnd; index--) {
            char character = content.charAt(index);
            if (character == '\n' || character == '。' || character == '！' || character == '？'
                    || character == '.' || character == '!' || character == '?' || character == ';' || character == '；') {
                return index + 1;
            }
        }
        return limit;
    }

    private boolean hasChildHeading(List<Node> topLevelNodes, int currentIndex, int currentLevel) {
        for (int i = currentIndex + 1; i < topLevelNodes.size(); i++) {
            Node nextNode = topLevelNodes.get(i);
            if (!(nextNode instanceof Heading nextHeading)) {
                continue;
            }
            if (nextHeading.getLevel() <= currentLevel) {
                return false;
            }
            return true;
        }
        return false;
    }

    private MarkdownParserService.SectionType resolveSectionType(String title, boolean hasChildren) {
        if (hasChildren) {
            return MarkdownParserService.SectionType.PARENT_OVERVIEW;
        }
        if (isQaLeafTitle(title)) {
            return MarkdownParserService.SectionType.LEAF_QA;
        }
        return MarkdownParserService.SectionType.LEAF_CONTENT;
    }

    private boolean isQaLeafTitle(String title) {
        if (title == null) {
            return false;
        }
        String normalized = title.trim();
        return "回答".equals(normalized)
                || "原理".equals(normalized)
                || "总结".equals(normalized)
                || "方案".equals(normalized);
    }

    private int pathDepth(String contentPath) {
        if (contentPath == null || contentPath.trim().isEmpty()) {
            return 0;
        }
        return contentPath.split(" > ").length;
    }

    private String extractHeadingText(Heading heading) {
        StringBuilder text = new StringBuilder();
        Node child = heading.getFirstChild();
        while (child != null) {
            String childText = extractPlainText(child);
            if (childText != null && !childText.trim().isEmpty()) {
                if (text.length() > 0) {
                    text.append(" ");
                }
                text.append(childText);
            }
            child = child.getNext();
        }
        return text.toString().trim();
    }

    private String extractNodeContent(Node node, String markdown) {
        if (node == null) {
            return null;
        }
        if (node instanceof TableBlock) {
            return extractTableMarkdown(node, markdown);
        }
        return extractPlainText(node);
    }

    private String extractTableMarkdown(Node tableNode, String markdown) {
        if (markdown == null) {
            return extractPlainText(tableNode);
        }

        try {
            BasedSequence chars = tableNode.getChars();
            if (chars != null && chars.length() > 0) {
                int startOffset = chars.getStartOffset();
                int endOffset = chars.getEndOffset();
                if (startOffset >= 0 && endOffset <= markdown.length() && startOffset < endOffset) {
                    return markdown.substring(startOffset, endOffset).trim();
                }
            }
            return extractPlainText(tableNode);
        } catch (Exception e) {
            log.warn("提取表格 Markdown 失败，退回纯文本提取: {}", e.getMessage());
            return extractPlainText(tableNode);
        }
    }

    private void collectMarkdownTables(Node node, String markdown, List<MarkdownTable> tables) {
        if (node instanceof TableBlock) {
            BasedSequence chars = node.getChars();
            if (chars == null || chars.isEmpty()) {
                return;
            }
            int startOffset = chars.getStartOffset();
            int endOffset = chars.getEndOffset();
            if (startOffset < 0 || endOffset <= startOffset || endOffset > markdown.length()) {
                return;
            }
            String content = markdown.substring(startOffset, endOffset).trim();
            if (!content.isEmpty()) {
                tables.add(new MarkdownTable(
                        content,
                        lineNumberAt(markdown, startOffset),
                        lineNumberAt(markdown, endOffset - 1)
                ));
            }
            return;
        }

        Node child = node.getFirstChild();
        while (child != null) {
            collectMarkdownTables(child, markdown, tables);
            child = child.getNext();
        }
    }

    private int lineNumberAt(String content, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (content.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String extractPlainText(Node node) {
        if (node == null) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        extractTextRecursive(node, text);
        return text.length() > 0 ? text.toString().trim() : null;
    }

    private void extractTextRecursive(Node node, StringBuilder text) {
        if (node == null || node instanceof Heading) {
            return;
        }

        Node child = node.getFirstChild();
        if (child != null) {
            boolean firstChild = true;
            while (child != null) {
                if (!firstChild && text.length() > 0) {
                    if (child instanceof Block) {
                        if (!text.toString().endsWith("\n")) {
                            text.append("\n");
                        }
                    } else {
                        text.append(" ");
                    }
                }
                extractTextRecursive(child, text);
                child = child.getNext();
                firstChild = false;
            }
            return;
        }

        try {
            BasedSequence chars = node.getChars();
            if (chars != null && chars.length() > 0) {
                String nodeText = chars.toString().trim();
                if (!nodeText.isEmpty()) {
                    if (text.length() > 0 && !text.toString().endsWith("\n")) {
                        text.append(" ");
                    }
                    text.append(nodeText);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
