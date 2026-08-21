package com.kama.jchatmind.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import java.io.InputStream;
import java.util.List;

/**
 * Markdown 解析服务接口
 */
public interface MarkdownParserService {
    /**
     * 解析 Markdown 文件，提取标题和对应的内容
     *
     * @param inputStream Markdown 文件输入流
     * @return 标题和内容的列表，每个元素包含标题和该标题下的内容
     */
    List<MarkdownSection> parseMarkdown(InputStream inputStream);

    default List<MarkdownSection> parseHtml(InputStream inputStream) {
        return parseMarkdown(inputStream);
    }

    default List<MarkdownSection> parsePdf(InputStream inputStream) {
        throw new UnsupportedOperationException("PDF 解析尚未配置");
    }
    
    /**
     * Markdown 章节数据类
     */
    @Data
    @AllArgsConstructor
    @ToString
    class MarkdownSection {
        private String title;
        private String content;
        private String contentPath;
        private String parentContentPath;
        private int headingLevel;
        private boolean hasChildren;
        private SectionType sectionType;
        private int pathDepth;
        private int localContentLength;
        private Integer pageNumber;

        public MarkdownSection(
                String title,
                String content,
                String contentPath,
                String parentContentPath,
                int headingLevel,
                boolean hasChildren,
                SectionType sectionType,
                int pathDepth,
                int localContentLength
        ) {
            this(title, content, contentPath, parentContentPath, headingLevel, hasChildren,
                    sectionType, pathDepth, localContentLength, null);
        }
    }

    enum SectionType {
        PARENT_OVERVIEW,
        LEAF_CONTENT,
        LEAF_QA
    }
}
