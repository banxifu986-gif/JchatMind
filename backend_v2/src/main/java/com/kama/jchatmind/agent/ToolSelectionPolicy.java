package com.kama.jchatmind.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ToolSelectionPolicy {

    private static final List<String> DIRECT_PHRASES = List.of(
            "你好", "您好", "嗨", "hello", "hi", "谢谢", "感谢", "再见", "拜拜"
    );
    private static final List<String> EMAIL_TERMS = List.of(
            "发邮件", "发送邮件", "写邮件", "替我发", "帮我发", "send email"
    );
    private static final List<String> DATABASE_TERMS = List.of(
            "查询数据库", "查数据库", "数据库查询", "执行sql", "执行 sql", "sql查询", "sql 查询",
            "database query", "select "
    );
    private static final List<String> WEATHER_TERMS = List.of(
            "天气", "气温", "温度", "下雨", "weather", "forecast"
    );
    private static final List<String> DATE_TERMS = List.of("今天几号", "当前日期", "今天日期", "what date", "date now");
    private static final List<String> CITY_TERMS = List.of(
            "所在城市", "当前城市", "哪个城市", "what city", "current city"
    );
    private static final List<String> FILE_READ_TERMS = List.of(
            "读取文件", "读文件", "查看文件", "打开文件", "文件内容", "列出文件", "列出目录",
            "查看目录", "read file", "list files"
    );
    private static final List<String> FILE_WRITE_TERMS = List.of(
            "写入文件", "写文件", "创建文件", "修改文件", "覆盖文件", "write file"
    );
    private static final List<String> FILE_APPEND_TERMS = List.of("追加文件", "追加内容", "append");
    private static final List<String> FILE_DELETE_TERMS = List.of("删除文件", "delete file");
    private static final List<String> DIRECTORY_CREATE_TERMS = List.of("创建目录", "create directory");
    private static final List<String> EXPLANATION_TERMS = List.of(
            "如何", "怎么", "怎样", "教程", "规范", "说明", "介绍", "什么是", "why", "how to"
    );
    private static final List<String> KNOWLEDGE_TERMS = List.of(
            "项目", "知识库", "文档", "规范", "架构", "接口", "故障", "部署", "发布",
            "查询", "检索", "搜索", "查找", "资料", "信息", "事实", "说明", "方案", "原理", "根据资料", "为什么", "如何", "怎么",
            "java", "spring", "rag", "agent", "mcp", "api"
    );

    public List<ToolCallback> select(List<ToolCallback> availableTools, String query) {
        if (availableTools == null || availableTools.isEmpty() || !StringUtils.hasText(query)) {
            return List.of();
        }

        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (isDirect(normalized)) {
            return List.of();
        }

        Set<String> selectedNames = new LinkedHashSet<>();
        boolean explanationRequest = containsAny(normalized, EXPLANATION_TERMS);
        if (!explanationRequest && containsAny(normalized, EMAIL_TERMS)) {
            selectedNames.add("sendEmail");
        }
        if (!explanationRequest && containsAny(normalized, DATABASE_TERMS)) {
            selectedNames.add("databaseQuery");
        }
        if (!explanationRequest && containsAny(normalized, WEATHER_TERMS)) {
            selectedNames.add("weather");
        }
        if (!explanationRequest && containsAny(normalized, DATE_TERMS)) {
            selectedNames.add("getDate");
        }
        if (!explanationRequest && containsAny(normalized, CITY_TERMS)) {
            selectedNames.add("getCity");
        }
        if (!explanationRequest && containsAny(normalized, FILE_READ_TERMS)) {
            selectedNames.add("readFile");
            selectedNames.add("listFiles");
        }
        if (!explanationRequest && containsAny(normalized, FILE_WRITE_TERMS)) {
            selectedNames.add("writeFile");
        }
        if (!explanationRequest && containsAny(normalized, FILE_APPEND_TERMS)) {
            selectedNames.add("appendToFile");
        }
        if (!explanationRequest && containsAny(normalized, FILE_DELETE_TERMS)) {
            selectedNames.add("deleteFile");
        }
        if (!explanationRequest && containsAny(normalized, DIRECTORY_CREATE_TERMS)) {
            selectedNames.add("createDirectory");
        }
        if (containsAny(normalized, KNOWLEDGE_TERMS)) {
            selectedNames.add("KnowledgeTool");
        }

        return availableTools.stream()
                .filter(callback -> selectedNames.contains(callback.getToolDefinition().name()))
                .toList();
    }

    private boolean isDirect(String query) {
        String compact = query.replaceAll("[\\s\\p{Punct}，。！？、；：‘’“”]", "");
        return DIRECT_PHRASES.contains(compact);
    }

    private boolean containsAny(String query, List<String> terms) {
        return terms.stream().anyMatch(query::contains);
    }
}
