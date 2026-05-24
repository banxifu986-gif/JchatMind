package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.MarkdownParserService;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class MarkdownParserServiceImpl implements MarkdownParserService {

    private final Parser parser;
    private String originalMarkdownContent;

    public MarkdownParserServiceImpl() {
        MutableDataSet options = new MutableDataSet();
        this.parser = Parser.builder(options).build();
    }

    @Override
    public List<MarkdownSection> parseMarkdown(InputStream inputStream) {
        try {
            originalMarkdownContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Document document = parser.parse(originalMarkdownContent);

            List<MarkdownSection> sections = new ArrayList<>();
            extractSections(document, sections);

            log.info("解析 Markdown 完成，共提取 {} 个章节", sections.size());
            return sections;
        } catch (Exception e) {
            log.error("解析 Markdown 失败", e);
            throw new RuntimeException("解析 Markdown 失败: " + e.getMessage(), e);
        }
    }

    private void extractSections(Document document, List<MarkdownSection> sections) {
        List<Node> topLevelNodes = new ArrayList<>();
        Node child = document.getFirstChild();
        while (child != null) {
            topLevelNodes.add(child);
            child = child.getNext();
        }

        List<String> currentPathTitles = new ArrayList<>();
        List<Integer> currentPathLevels = new ArrayList<>();

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

                String content = extractNodeContent(nextNode);
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

    private String extractNodeContent(Node node) {
        if (node == null) {
            return null;
        }
        if (node instanceof TableBlock) {
            return extractTableMarkdown(node);
        }
        return extractPlainText(node);
    }

    private String extractTableMarkdown(Node tableNode) {
        if (originalMarkdownContent == null) {
            return extractPlainText(tableNode);
        }

        try {
            BasedSequence chars = tableNode.getChars();
            if (chars != null && chars.length() > 0) {
                int startOffset = chars.getStartOffset();
                int endOffset = chars.getEndOffset();
                if (startOffset >= 0 && endOffset <= originalMarkdownContent.length() && startOffset < endOffset) {
                    return originalMarkdownContent.substring(startOffset, endOffset).trim();
                }
            }
            return extractPlainText(tableNode);
        } catch (Exception e) {
            log.warn("提取表格 Markdown 失败，退回纯文本提取: {}", e.getMessage());
            return extractPlainText(tableNode);
        }
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
