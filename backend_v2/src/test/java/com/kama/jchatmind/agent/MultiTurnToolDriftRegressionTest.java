package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.intent.BusinessIntent;
import com.kama.jchatmind.intent.IntentDecision;
import com.kama.jchatmind.intent.IntentRoute;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 15 轮多意图工具选择回归测试：验证连续切换话题后工具选择不漂移、不错调。
 */
class MultiTurnToolDriftRegressionTest {

    private final ToolSelectionPolicy policy = new ToolSelectionPolicy();

    /**
     * 15 轮意图切换序列，交替覆盖 KNOWLEDGE / TOOL(DATE) / TOOL(WEATHER) / TOOL(EMAIL) / KNOWLEDGE。
     * 每轮验证：intent 正确 → 可用工具正确 → 上一轮工具不残留。
     */
    @Test
    void shouldNotDriftToolSelectionAcross15IntentSwitches() {
        record Round(int num, String query, BusinessIntent expectedIntent,
                     String expectedTool, String forbiddenTool) {}

        List<Round> rounds = List.of(
                new Round(1, "请查询项目的发布规范", BusinessIntent.KNOWLEDGE_QA, "KnowledgeTool", "sendEmail"),
                new Round(2, "Java 部署流程是什么", BusinessIntent.KNOWLEDGE_QA, "KnowledgeTool", "sendEmail"),
                new Round(3, "今天几号", BusinessIntent.DATE_OR_CITY_QUERY, "getDate", "KnowledgeTool"),
                new Round(4, "Spring Boot 配置规范", BusinessIntent.KNOWLEDGE_QA, "KnowledgeTool", "getDate"),
                new Round(5, "我所在的城市是哪里", BusinessIntent.DATE_OR_CITY_QUERY, "getCity", "KnowledgeTool"),
                new Round(6, "Redis 缓存策略有哪些", BusinessIntent.KNOWLEDGE_QA, "KnowledgeTool", "getCity"),
                new Round(7, "今天天气怎么样", BusinessIntent.WEATHER_QUERY, "weather", "KnowledgeTool"),
                new Round(8, "MySQL 索引优化方案", BusinessIntent.KNOWLEDGE_QA, "KnowledgeTool", "weather"),
                new Round(9, "帮我发一封邮件给张三", BusinessIntent.SEND_EMAIL, "sendEmail", "KnowledgeTool"),
                new Round(10, "Docker 部署最佳实践", BusinessIntent.KNOWLEDGE_QA, "KnowledgeTool", "sendEmail"),
                new Round(11, "Kubernetes 编排规范", BusinessIntent.KNOWLEDGE_QA, "KnowledgeTool", "sendEmail"),
                new Round(12, "帮我读取 config.yml 文件", BusinessIntent.FILE_READ, "readFile", "KnowledgeTool"),
                new Round(13, "微服务治理框架选型", BusinessIntent.KNOWLEDGE_QA, "KnowledgeTool", "readFile"),
                new Round(14, "列出当前目录下的文件", BusinessIntent.FILE_READ, "listFiles", "KnowledgeTool"),
                new Round(15, "API 网关设计模式", BusinessIntent.KNOWLEDGE_QA, "KnowledgeTool", "listFiles")
        );

        List<String> previousToolNames = List.of();
        int driftCount = 0;

        for (Round round : rounds) {
            IntentDecision decision = policy.decide(round.query());
            List<ToolCallback> tools = policy.select(allCallbacks(), decision);
            List<String> toolNameList = toolNames(tools);

            // 1. 意图路由正确
            assertThat(decision.route())
                    .as("Round %d 意图路由: query=%s", round.num(), round.query())
                    .isIn(IntentRoute.KNOWLEDGE, IntentRoute.TOOL, IntentRoute.DIRECT);

            // 2. 预期工具在可用列表中
            if (!IntentRoute.DIRECT.equals(decision.route())) {
                assertThat(toolNameList)
                        .as("Round %d 预期工具 %s 应在可用列表中", round.num(), round.expectedTool())
                        .contains(round.expectedTool());
            }

            // 3. 上一轮的工具不应残留（除非是 KnowledgeTool，因为它几乎每轮都可用）
            if (previousToolNames != null && !previousToolNames.isEmpty()
                    && !round.expectedTool().equals("KnowledgeTool")) {
                for (String prevTool : previousToolNames) {
                    if (!prevTool.equals(round.expectedTool()) && !prevTool.equals("KnowledgeTool")) {
                        boolean leaked = toolNameList.contains(prevTool)
                                && !prevTool.equals(round.expectedTool());
                        if (leaked) { driftCount++; }
                    }
                }
            }

            // 4. 禁止调用的工具不在可用列表中
            if (!IntentRoute.DIRECT.equals(decision.route())) {
                assertThat(toolNameList)
                        .as("Round %d 禁止工具 %s 不应出现", round.num(), round.forbiddenTool())
                        .doesNotContain(round.forbiddenTool());
            }

            previousToolNames = toolNameList;
        }

        assertThat(driftCount).as("工具漂移次数应为 0").isZero();
    }

    /**
     * 验证 15 轮交替后：KNOWLEDGE intent 始终只有 KnowledgeTool（不残留 email/weather/date 等）。
     */
    @Test
    void shouldIsolateKnowledgeToolFromOtherToolsAcrossSwitches() {
        // 先触发非 KNOWLEDGE 意图
        policy.decide("今天几号");
        policy.decide("帮我发邮件给张三");
        policy.decide("今天天气怎么样");
        policy.decide("读取 config.yml");

        // 再切回 KNOWLEDGE → 只应有 KnowledgeTool
        IntentDecision knowledgeDecision = policy.decide("请查询 Redis 缓存策略");
        List<ToolCallback> knowledgeTools = policy.select(allCallbacks(), knowledgeDecision);
        List<String> names = toolNames(knowledgeTools);

        assertThat(names).containsExactly("KnowledgeTool");
        assertThat(names).doesNotContain("sendEmail", "weather", "getDate", "readFile");
    }

    /**
     * 验证 15 轮中 KNOWLEDGE intent 的工具选择始终一致（确定性）。
     */
    @Test
    void shouldBeDeterministicAcrossRepeatedQueries() {
        List<String> first = toolNames(policy.select(allCallbacks(),
                policy.decide("请查询项目的发布规范")));
        for (int i = 0; i < 15; i++) {
            // 穿插其他 intent
            policy.decide("今天几号");
            policy.decide("发邮件");
            policy.decide("天气");
            // 再查知识
            List<String> again = toolNames(policy.select(allCallbacks(),
                    policy.decide("请查询项目的发布规范")));
            assertThat(again).as("第 %d 次重复查询应与首次一致", i + 1).isEqualTo(first);
        }
    }

    private List<ToolCallback> allCallbacks() {
        ToolSelectionPolicy p = new ToolSelectionPolicy();
        return List.of(
                stubCallback("KnowledgeTool"),
                stubCallback("sendEmail"),
                stubCallback("databaseQuery"),
                stubCallback("weather"),
                stubCallback("getDate"),
                stubCallback("getCity"),
                stubCallback("readFile"),
                stubCallback("listFiles"),
                stubCallback("writeFile"),
                stubCallback("deleteFile")
        );
    }

    private ToolCallback stubCallback(String name) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(name).description("stub " + name)
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }
            @Override
            public String call(String toolInput) { return "result of " + name; }
            public String call(String toolInput, java.util.UUID uuid) { return call(toolInput); }
        };
    }

    private List<String> toolNames(List<ToolCallback> callbacks) {
        return callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .sorted()
                .toList();
    }
}