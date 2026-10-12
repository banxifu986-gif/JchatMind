package com.kama.jchatmind.intent;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class IntentRouter {

    public static final String DECISION_VERSION = "intent-v2";
    public static final String POLICY_VERSION = "intent-policy-v5";

    private static final List<String> DIRECT_PHRASES = List.of(
            "你好", "您好", "嗨", "hello", "hi", "谢谢", "感谢", "再见", "拜拜"
    );
    private static final List<String> EXPLANATION_TERMS = List.of(
            "如何", "怎么", "怎样", "教程", "规范", "说明", "介绍", "什么是", "为什么", "why", "how to"
    );
    private static final List<String> KNOWLEDGE_TERMS = List.of(
            "项目", "知识库", "文档", "规范", "架构", "接口", "故障", "部署", "发布",
            "查询", "检索", "搜索", "查找", "资料", "信息", "事实", "说明", "方案", "原理",
            "根据资料", "java", "spring", "rag", "agent", "mcp", "api"
    );
    private static final List<String> EMAIL_ACTION_TERMS = List.of(
            "发邮件", "发送邮件", "写邮件", "替我发", "帮我发", "邮件发给", "邮件发到",
            "发到邮箱", "发送到邮箱", "send email"
    );
    private static final List<String> EXTERNAL_LOOKUP_TERMS = List.of(
            "联网查询", "网上查询", "网上搜索", "网页搜索", "互联网搜索", "查询最新",
            "搜索最新", "最新官方", "最新的官方", "最新版本", "最新发布", "官网发布说明",
            "official docs", "latest release", "web search"
    );
    private static final List<String> EXTERNAL_OFFICIAL_TERMS = List.of("官方", "官网", "official");
    private static final List<String> DATABASE_ACTION_TERMS = List.of(
            "查询数据库", "查数据库", "数据库查询", "执行sql", "执行 sql", "sql查询", "sql 查询",
            "从数据库读取", "数据库读取", "database query", "select "
    );
    private static final List<String> WEATHER_TERMS = List.of(
            "天气", "气温", "温度", "下雨", "weather", "forecast"
    );
    private static final List<String> DATE_TERMS = List.of(
            "今天几号", "当前日期", "今天日期", "今天的日期", "今日日期", "今天是什么日期",
            "今天日期是什么", "what date", "date now"
    );
    private static final List<String> CITY_TERMS = List.of(
            "所在城市", "当前城市", "哪个城市", "what city", "current city"
    );
    private static final List<String> FILE_READ_TERMS = List.of(
            "读取文件", "读文件", "查看文件", "打开文件", "文件内容", "列出文件", "列出目录",
            "文件的内容", "查看目录", "read file", "list files"
    );
    private static final List<String> FILE_READ_VERBS = List.of(
            "打开", "读取", "查看", "读", "open", "read", "view"
    );
    private static final List<String> FILE_LIST_TERMS = List.of(
            "列出文件", "列出目录", "查看目录", "list files", "list directory"
    );
    private static final List<String> KNOWLEDGE_FOLLOW_UP_REFERENCES = List.of(
            "前面提到", "前面说", "刚才提到", "刚才说", "上文", "前文", "上一轮", "之前提到", "之前说"
    );
    private static final List<String> KNOWLEDGE_FOLLOW_UP_SUBJECTS = List.of(
            "回滚", "超时", "限制", "架构", "发布", "配置", "缓存", "接口", "规范", "知识库", "项目"
    );
    private static final List<String> CONTEXTUAL_FOLLOW_UP_QUERIES = List.of(
            "为什么", "为什么呢", "怎么做", "怎么做呢", "具体呢", "然后呢", "还有呢",
            "那呢", "那么呢", "它呢", "这个呢", "再说说", "能展开说说吗"
    );
    private static final List<String> KNOWLEDGE_NAVIGATION_TERMS = List.of(
            "定位", "找到", "查找", "章节", "目录", "标题", "页码", "第几页", "哪一节", "哪一章"
    );
    private static final List<String> FILE_MUTATION_TERMS = List.of(
            "写入文件", "写文件", "创建文件", "修改文件", "覆盖文件", "追加文件", "追加内容",
            "追加到文件", "删除文件", "创建目录", "write file", "append", "delete file", "create directory"
    );
    private static final List<String> COMPOSITE_MARKERS = List.of(
            "然后", "再发给", "再发邮件", "再发送", "再通知", "再告知", "再删除", "再写入",
            "再创建", "再修改", "再追加", "之后", "并且", "并邮件", "并通知", "并告知",
            "并发送", "并删除", "并写入", "并创建", "随后", "同时"
    );
    private static final List<String> COMPOSITE_ACTION_TERMS = List.of(
            "发邮件", "发送邮件", "写邮件", "邮件发给", "发给", "通知", "告知", "转发给",
            "删除", "写入", "修改", "创建文件", "send email"
    );
    private static final List<String> BLOCKED_TERMS = List.of(
            "管理员密码", "数据库密码", "其他用户的私有", "其他用户私有", "别人的私有", "他人私有",
            "导出所有知识库", "导出全部知识库", "同事账号下", "同事的薪资", "私人薪资", "薪资信息", "工资信息"
    );
    private static final List<String> PROMPT_INJECTION_TERMS = List.of(
            "忽略系统", "忽略规则", "绕过安全", "不要遵守", "system prompt"
    );
    private static final List<String> LOW_INFORMATION_TERMS = List.of(
            "这个", "那个", "处理一下", "搞一下", "弄一下"
    );
    private static final List<String> ACTION_NEGATION_TERMS = List.of(
            "不想", "不需要", "不要", "无需", "不用", "不必", "别", "禁止", "don't", "do not", "not"
    );
    private static final List<String> NEGATABLE_ACTION_TERMS = List.of(
            "发邮件", "发送邮件", "邮件发给", "邮件发到", "send email", "查询数据库", "查数据库",
            "执行sql", "执行 sql", "天气", "weather", "今天几号", "今天日期", "当前日期", "读取文件",
            "查看文件", "打开文件", "read file", "list files", "写入文件", "修改文件", "删除文件", "write file"
    );

    public IntentDecision decide(String query) {
        return decide(query, null);
    }

    public IntentDecision decide(String query, String previousUserQuery) {
        String normalized = normalize(query);
        if (!StringUtils.hasText(normalized)) {
            return clarify("EMPTY_QUERY");
        }
        if (isBlocked(normalized)) {
            return abstain("BLOCKED_OR_INJECTION");
        }
        if (isDirect(normalized)) {
            return decision(
                    BusinessIntent.DIRECT_CHAT,
                    null,
                    IntentRoute.DIRECT,
                    IntentRiskLevel.NONE,
                    0.99D,
                    List.of(),
                    false,
                    "DIRECT_PHRASE"
            );
        }
        if (isNegatedAction(normalized)) {
            boolean knowledgeSignal = containsAny(normalized, KNOWLEDGE_TERMS);
            return decision(
                    knowledgeSignal ? BusinessIntent.KNOWLEDGE_QA : BusinessIntent.GENERAL_EXPLANATION,
                    knowledgeSignal ? QueryIntent.FACTOID : null,
                    knowledgeSignal ? IntentRoute.KNOWLEDGE : IntentRoute.DIRECT,
                    IntentRiskLevel.READ_ONLY,
                    0.90D,
                    knowledgeSignal ? List.of("KnowledgeTool") : List.of(),
                    false,
                    "NEGATED_ACTION"
            );
        }
        if (isComposite(normalized)) {
            List<String> capabilities = containsAny(normalized, KNOWLEDGE_TERMS)
                    ? List.of("KnowledgeTool")
                    : List.of();
            return decision(
                    BusinessIntent.COMPOSITE,
                    null,
                    IntentRoute.COMPOSITE,
                    IntentRiskLevel.MIXED,
                    0.96D,
                    capabilities,
                    true,
                    "COMPOSITE_REQUEST"
            );
        }
        if (isExternalLookupRequest(normalized)) {
            return decision(
                    BusinessIntent.EXTERNAL_LOOKUP,
                    QueryIntent.FACTOID,
                    IntentRoute.EXTERNAL,
                    IntentRiskLevel.EXTERNAL_READ,
                    0.91D,
                    List.of("externalLookup"),
                    false,
                    "EXTERNAL_LOOKUP_SIGNAL"
            );
        }
        if (containsAny(normalized, LOW_INFORMATION_TERMS)
                && !containsAny(normalized, KNOWLEDGE_TERMS)
                && !hasExplicitAction(normalized)) {
            return clarify("LOW_INFORMATION");
        }
        if (isKnowledgeFollowUp(normalized, previousUserQuery)) {
            return decision(
                    BusinessIntent.KNOWLEDGE_FOLLOW_UP,
                    QueryIntent.FOLLOW_UP,
                    IntentRoute.KNOWLEDGE,
                    IntentRiskLevel.READ_ONLY,
                    0.90D,
                    List.of("KnowledgeTool"),
                    false,
                    "KNOWLEDGE_FOLLOW_UP_SIGNAL"
            );
        }

        boolean explanationRequest = isExplanationRequest(normalized);
        if (explanationRequest && containsAny(normalized, KNOWLEDGE_TERMS)) {
            return decision(
                    BusinessIntent.KNOWLEDGE_QA,
                    QueryIntent.FACTOID,
                    IntentRoute.KNOWLEDGE,
                    IntentRiskLevel.READ_ONLY,
                    0.92D,
                    List.of("KnowledgeTool"),
                    false,
                    "KNOWLEDGE_SIGNAL"
            );
        }
        if (explanationRequest) {
            List<String> capabilities = explanationNeedsKnowledge(normalized)
                    ? List.of("KnowledgeTool")
                    : List.of();
            return decision(
                    BusinessIntent.GENERAL_EXPLANATION,
                    null,
                    capabilities.isEmpty() ? IntentRoute.DIRECT : IntentRoute.KNOWLEDGE,
                    IntentRiskLevel.READ_ONLY,
                    0.90D,
                    capabilities,
                    false,
                    "EXPLANATION_REQUEST"
            );
        }
        if (containsAny(normalized, EMAIL_ACTION_TERMS)) {
            return decision(
                    BusinessIntent.SEND_EMAIL,
                    null,
                    IntentRoute.TOOL,
                    IntentRiskLevel.SIDE_EFFECT,
                    0.98D,
                    List.of("sendEmail"),
                    false,
                    "EXPLICIT_EMAIL_ACTION"
            );
        }
        if (containsAny(normalized, DATABASE_ACTION_TERMS)) {
            return decision(
                    BusinessIntent.DATABASE_QUERY,
                    QueryIntent.FACTOID,
                    IntentRoute.TOOL,
                    IntentRiskLevel.READ_ONLY,
                    0.95D,
                    List.of("databaseQuery"),
                    false,
                    "EXPLICIT_DATABASE_QUERY"
            );
        }
        if (containsAny(normalized, WEATHER_TERMS)) {
            return decision(
                    BusinessIntent.WEATHER_QUERY,
                    QueryIntent.FACTOID,
                    IntentRoute.TOOL,
                    IntentRiskLevel.EXTERNAL_READ,
                    0.95D,
                    List.of("weather"),
                    false,
                    "WEATHER_SIGNAL"
            );
        }
        if (containsAny(normalized, DATE_TERMS) || containsAny(normalized, CITY_TERMS)) {
            return decision(
                    BusinessIntent.DATE_OR_CITY_QUERY,
                    QueryIntent.FACTOID,
                    IntentRoute.TOOL,
                    IntentRiskLevel.READ_ONLY,
                    0.95D,
                    containsAny(normalized, DATE_TERMS) ? List.of("getDate") : List.of("getCity"),
                    false,
                    "DATE_OR_CITY_SIGNAL"
            );
        }
        if (isFileReadRequest(normalized)) {
            return decision(
                    BusinessIntent.FILE_READ,
                    QueryIntent.FACTOID,
                    IntentRoute.TOOL,
                    IntentRiskLevel.READ_ONLY,
                    0.95D,
                    fileReadCapabilities(normalized),
                    false,
                    "EXPLICIT_FILE_READ"
            );
        }
        if (containsAny(normalized, FILE_MUTATION_TERMS)) {
            return decision(
                    BusinessIntent.FILE_MUTATION,
                    null,
                    IntentRoute.TOOL,
                    IntentRiskLevel.SIDE_EFFECT,
                    0.96D,
                    mutationCapabilities(normalized),
                    false,
                    "EXPLICIT_FILE_MUTATION"
            );
        }
        if (isKnowledgeNavigation(normalized)) {
            return decision(
                    BusinessIntent.KNOWLEDGE_NAVIGATION,
                    QueryIntent.NAVIGATION,
                    IntentRoute.KNOWLEDGE,
                    IntentRiskLevel.READ_ONLY,
                    0.92D,
                    List.of("KnowledgeTool"),
                    false,
                    "KNOWLEDGE_NAVIGATION_SIGNAL"
            );
        }
        if (containsAny(normalized, KNOWLEDGE_TERMS)) {
            return decision(
                    BusinessIntent.KNOWLEDGE_QA,
                    QueryIntent.FACTOID,
                    IntentRoute.KNOWLEDGE,
                    IntentRiskLevel.READ_ONLY,
                    0.92D,
                    List.of("KnowledgeTool"),
                    false,
                    "KNOWLEDGE_SIGNAL"
            );
        }
        return decision(
                BusinessIntent.GENERAL_EXPLANATION,
                null,
                IntentRoute.DIRECT,
                IntentRiskLevel.READ_ONLY,
                0.70D,
                List.of(),
                false,
                "NO_TOOL_SIGNAL"
        );
    }

    private List<String> mutationCapabilities(String normalized) {
        if (normalized.contains("删除") || normalized.contains("delete file")) {
            return List.of("deleteFile");
        }
        if (normalized.contains("追加") || normalized.contains("append")) {
            return List.of("appendToFile");
        }
        if (normalized.contains("目录") || normalized.contains("directory")) {
            return List.of("createDirectory");
        }
        return List.of("writeFile");
    }

    private boolean explanationNeedsKnowledge(String normalized) {
        return containsAny(normalized, KNOWLEDGE_TERMS);
    }

    private boolean isFileReadRequest(String normalized) {
        return containsAny(normalized, FILE_READ_TERMS)
                || (containsAny(normalized, FILE_READ_VERBS) && hasRecognizedFileExtension(normalized));
    }

    private boolean isExternalLookupRequest(String normalized) {
        return containsAny(normalized, EXTERNAL_LOOKUP_TERMS)
                || (normalized.contains("最新") && containsAny(normalized, EXTERNAL_OFFICIAL_TERMS));
    }

    private List<String> fileReadCapabilities(String normalized) {
        return containsAny(normalized, FILE_LIST_TERMS)
                ? List.of("listFiles")
                : List.of("readFile");
    }

    private boolean isKnowledgeFollowUp(String normalized, String previousUserQuery) {
        String previous = normalize(previousUserQuery);
        if (!containsAny(previous, KNOWLEDGE_TERMS)) {
            return false;
        }
        return containsAny(normalized, KNOWLEDGE_FOLLOW_UP_REFERENCES)
                || ((normalized.contains("那") || normalized.contains("那么"))
                && containsAny(normalized, KNOWLEDGE_FOLLOW_UP_SUBJECTS))
                || CONTEXTUAL_FOLLOW_UP_QUERIES.contains(
                normalized.replaceAll("[\\s\\p{Punct}，。！？、；：‘’“”]", "")
        );
    }

    private boolean isKnowledgeNavigation(String normalized) {
        return containsAny(normalized, KNOWLEDGE_NAVIGATION_TERMS)
                && (hasRecognizedFileExtension(normalized)
                || containsAny(normalized, List.of("文档", "手册", "runbook", "docs/")));
    }

    private boolean hasRecognizedFileExtension(String normalized) {
        return normalized.matches(".*\\.(?:md|markdown|txt|log|json|java|ya?ml|pdf|csv|xml)(?:$|[?#\\s].*)");
    }

    private boolean isExplanationRequest(String normalized) {
        if (!containsAny(normalized, EXPLANATION_TERMS)) {
            return false;
        }
        if (!hasExplicitAction(normalized)) {
            return true;
        }
        return firstIndex(normalized, EXPLANATION_TERMS) < firstActionIndex(normalized);
    }

    private boolean hasExplicitAction(String normalized) {
        return containsAny(normalized, EMAIL_ACTION_TERMS)
                || containsAny(normalized, DATABASE_ACTION_TERMS)
                || containsAny(normalized, WEATHER_TERMS)
                || containsAny(normalized, DATE_TERMS)
                || containsAny(normalized, CITY_TERMS)
                || containsAny(normalized, FILE_READ_TERMS)
                || containsAny(normalized, FILE_MUTATION_TERMS);
    }

    private int firstActionIndex(String normalized) {
        return Math.min(
                Math.min(firstIndex(normalized, EMAIL_ACTION_TERMS), firstIndex(normalized, DATABASE_ACTION_TERMS)),
                Math.min(
                        Math.min(firstIndex(normalized, WEATHER_TERMS), firstIndex(normalized, DATE_TERMS)),
                        Math.min(
                                Math.min(firstIndex(normalized, CITY_TERMS), firstIndex(normalized, FILE_READ_TERMS)),
                                firstIndex(normalized, FILE_MUTATION_TERMS)
                        )
                )
        );
    }

    private int firstIndex(String normalized, List<String> terms) {
        return terms.stream()
                .mapToInt(normalized::indexOf)
                .filter(index -> index >= 0)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private boolean isBlocked(String normalized) {
        return containsAny(normalized, BLOCKED_TERMS)
                || (containsAny(normalized, PROMPT_INJECTION_TERMS)
                && (containsAny(normalized, COMPOSITE_ACTION_TERMS)
                || normalized.contains("调用")
                || normalized.contains("tool")));
    }

    private boolean isNegatedAction(String normalized) {
        for (String negation : ACTION_NEGATION_TERMS) {
            int negationIndex = normalized.indexOf(negation);
            if (negationIndex < 0) {
                continue;
            }
            int searchFrom = negationIndex + negation.length();
            for (String action : NEGATABLE_ACTION_TERMS) {
                int actionIndex = normalized.indexOf(action, searchFrom);
                if (actionIndex >= searchFrom && actionIndex - searchFrom <= 12) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isComposite(String normalized) {
        return containsAny(normalized, COMPOSITE_MARKERS)
                && containsAny(normalized, COMPOSITE_ACTION_TERMS)
                && (containsAny(normalized, KNOWLEDGE_TERMS) || containsAny(normalized, EXPLANATION_TERMS));
    }

    private boolean isDirect(String normalized) {
        String compact = normalized.replaceAll("[\\s\\p{Punct}，。！？、；：‘’“”]", "");
        return DIRECT_PHRASES.contains(compact);
    }

    private boolean containsAny(String query, List<String> terms) {
        return terms.stream().anyMatch(query::contains);
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private IntentDecision clarify(String reasonCode) {
        return decision(
                BusinessIntent.CLARIFY,
                null,
                IntentRoute.CLARIFY,
                IntentRiskLevel.NONE,
                0.40D,
                List.of(),
                true,
                reasonCode
        );
    }

    private IntentDecision abstain(String reasonCode) {
        return decision(
                BusinessIntent.ABSTAIN,
                null,
                IntentRoute.ABSTAIN,
                IntentRiskLevel.BLOCKED,
                1.0D,
                List.of(),
                false,
                reasonCode
        );
    }

    private IntentDecision decision(
            BusinessIntent businessIntent,
            QueryIntent queryIntent,
            IntentRoute route,
            IntentRiskLevel riskLevel,
            double confidence,
            List<String> candidateCapabilities,
            boolean requiresClarification,
            String reasonCode
    ) {
        return new IntentDecision(
                DECISION_VERSION,
                businessIntent,
                queryIntent,
                route,
                riskLevel,
                confidence,
                null,
                Map.of(),
                candidateCapabilities,
                requiresClarification,
                IntentDecisionSource.RULE,
                reasonCode,
                POLICY_VERSION
        );
    }
}
