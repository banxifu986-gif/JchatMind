package com.kama.jchatmind.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;

@SpringBootTest
public class McpToolLoadingTest {

    @Autowired
    private ApplicationContext ctx;

    @Autowired(required = false)
    private ToolCallbackProvider externalToolCallbackProvider;

    @Autowired(required = false)
    @Qualifier("mcpToolCallbackProvider")
    private ToolCallbackProvider mcpToolCallbackProvider;

    @Test
    void printAllToolCallbacks() {
        System.out.println("========== 工具加载诊断 ==========");

        // 1. ToolCallbackProvider 的 Bean
        System.out.println("[所有 ToolCallbackProvider Bean]");
        for (String name : ctx.getBeanNamesForType(ToolCallbackProvider.class)) {
            System.out.println("  Bean: " + name + " -> " + ctx.getBean(name, ToolCallbackProvider.class).getClass().getSimpleName());
        }

        // 2. MCP 客户端
        System.out.println("[MCP 客户端相关 Bean]");
        for (String name : ctx.getBeanDefinitionNames()) {
            if (name.toLowerCase().contains("mcp") && name.toLowerCase().contains("client")) {
                Object bean = ctx.getBean(name);
                System.out.println("  " + name + " -> " + bean.getClass().getName());
            }
        }

        // 3. mcpToolCallbacks (Spring AI 自动配置的 MCP 客户端工具)
        System.out.println("[mcpToolCallbacks - MCP 客户端工具]");
        try {
            ToolCallbackProvider mcpClientTools = ctx.getBean("mcpToolCallbacks", ToolCallbackProvider.class);
            ToolCallback[] callbacks = mcpClientTools.getToolCallbacks();
            if (callbacks.length == 0) {
                System.out.println("  (无工具 - MCP 客户端未连接或连接失败)");
            }
            for (ToolCallback tc : callbacks) {
                System.out.println("  - " + tc.getToolDefinition().name()
                        + " | " + tc.getToolDefinition().description());
            }
        } catch (Exception e) {
            System.out.println("  获取失败: " + e.getMessage());
        }

        // 4. 外部工具
        if (externalToolCallbackProvider != null) {
            System.out.println("[外部工具]");
            ToolCallback[] callbacks = externalToolCallbackProvider.getToolCallbacks();
            if (callbacks.length == 0) {
                System.out.println("  (无外部工具)");
            }
            for (ToolCallback tc : callbacks) {
                System.out.println("  - " + tc.getToolDefinition().name());
            }
        }

        // 5. 所有 MCP 相关 Bean
        System.out.println("[MCP 相关 Bean]");
        for (String name : ctx.getBeanDefinitionNames()) {
            if (name.toLowerCase().contains("mcp") || name.toLowerCase().contains("notion")) {
                System.out.println("  " + name + " -> " + ctx.getBean(name).getClass().getName());
            }
        }

        System.out.println("=================================");

        // 6. 检查配置
        System.out.println("[关键配置检查]");
        org.springframework.core.env.Environment env2 = ctx.getEnvironment();
        System.out.println("  NOTION_API_KEY=" + env2.getProperty("NOTION_API_KEY", "(未找到)"));
        System.out.println("  spring.ai.mcp.client.stdio.connections.notion.env.NOTION_API_KEY="
                + env2.getProperty("spring.ai.mcp.client.stdio.connections.notion.env.NOTION_API_KEY", "(未找到)"));
        System.out.println("  spring.ai.mcp.client.stdio.connections.notion.command="
                + env2.getProperty("spring.ai.mcp.client.stdio.connections.notion.command", "(未找到)"));
        System.out.println("=================================");
    }
}
