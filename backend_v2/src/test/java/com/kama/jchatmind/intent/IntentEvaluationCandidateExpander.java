package com.kama.jchatmind.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class IntentEvaluationCandidateExpander {

    static final String GENERATOR_VERSION = "seed-paraphrase-expansion-v4";

    private static final List<String> DEV_PREFIXES = List.of(
            "请", "麻烦", "劳烦", "我想确认，", "请问，", "想请教，", "方便的话，",
            "烦请，", "能不能帮我，", "请协助，"
    );
    private static final List<String> TEST_PREFIXES = List.of(
            "帮忙看下，", "想了解，", "方便帮我，", "请帮我确认，", "我想问一下，"
    );
    private static final List<String> DIRECT_QUERIES = List.of(
            "你好", "你好！", "你好。", "您好", "您好！", "嗨", "嗨！", "hello", "hello!", "hi",
            "hi!", "谢谢", "感谢", "再见", "拜拜", "你好！！", "您好。"
    );
    private static final List<String> CLARIFY_QUERIES = List.of(
            " ", "这个", "那个", "帮我处理一下", "处理一下这个", "搞一下", "弄一下", "麻烦处理一下",
            "帮我看一下这个", "处理这个", "这个帮忙", "请处理一下这个", "帮忙弄一下", "这个怎么办",
            "那个处理一下", "请帮我搞一下", "这个麻烦你", "能帮我弄一下吗", "那个帮我看下"
    );

    private IntentEvaluationCandidateExpander() {
    }

    static List<IntentEvaluationCase> expand(List<IntentEvaluationCase> seeds, String datasetId) {
        List<IntentEvaluationCase> expanded = new ArrayList<>();
        for (IntentEvaluationCase seed : seeds) {
            int variantCount;
            if ("dev".equals(seed.split())) {
                variantCount = DEV_PREFIXES.size();
            } else if ("test".equals(seed.split())) {
                variantCount = seed.caseId().endsWith("021")
                        || seed.caseId().endsWith("022")
                        || seed.caseId().endsWith("023")
                        || seed.caseId().endsWith("024")
                        || seed.caseId().endsWith("025")
                        || seed.caseId().endsWith("026")
                        || seed.caseId().endsWith("027")
                        || seed.caseId().endsWith("028")
                        || seed.caseId().endsWith("029")
                        || seed.caseId().endsWith("030")
                        ? 7
                        : 6;
            } else {
                throw new IllegalStateException("candidate seed split must be dev or test: " + seed.caseId());
            }
            for (int index = 0; index < variantCount; index++) {
                String query = variant(seed, index);
                List<String> tags = new ArrayList<>(seed.tags() == null ? List.of() : seed.tags());
                tags.add("synthetic-candidate");
                tags.add("pending-human-review");
                tags.add("seed-family:" + seed.caseId());
                expanded.add(new IntentEvaluationCase(
                        datasetId + "-" + seed.caseId()
                                + "-" + String.format(Locale.ROOT, "%02d", index + 1),
                        datasetId,
                        seed.split(),
                        query,
                        seed.previousUserQuery(),
                        seed.skipReason(),
                        seed.expectedBusinessIntent(),
                        seed.expectedRoute(),
                        seed.expectedRiskLevel(),
                        seed.expectedCapabilities(),
                        seed.shouldClarify(),
                        seed.shouldAbstain(),
                        List.copyOf(tags),
                        "pending_review"
                ));
            }
        }
        return List.copyOf(expanded);
    }

    private static String variant(IntentEvaluationCase seed, int index) {
        if (seed.expectedBusinessIntent() == BusinessIntent.DIRECT_CHAT) {
            int offset = "test".equals(seed.split()) ? 10 : 0;
            return DIRECT_QUERIES.get(Math.floorMod(offset + index, DIRECT_QUERIES.size()));
        }
        if (seed.expectedBusinessIntent() == BusinessIntent.CLARIFY) {
            int offset = "test".equals(seed.split()) ? 10 : 0;
            return CLARIFY_QUERIES.get(Math.floorMod(offset + index, CLARIFY_QUERIES.size()));
        }

        String prefix = index < DEV_PREFIXES.size()
                ? DEV_PREFIXES.get(index)
                : TEST_PREFIXES.get(index - DEV_PREFIXES.size());
        return prefix + seed.query();
    }
}
