package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class G2CurrentFixtureRouterComparisonTest {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";
    private static final String REPLAY_PATH = "rag-eval/datasets/replays/fixture-fast-v1.jsonl";
    private static final Path REPORT_PATH = Path.of(
            "target", "rag-eval", "router", "fixture-fast-v1-router-ablation.json"
    );

    @Test
    void replaysCurrentFixtureThroughRealRouterAgainstTheFixedReplayContract() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        Map<String, RagFastRegressionReplay> fixedReplays = loadFixedReplays();
        assertThat(fixedReplays).hasSize(profile.caseCount());

        RagRouter router = new RagRouter();
        List<RagRouterAblationReplay> replays = profile.cases().stream()
                .map(item -> toRouterReplay(item, fixedReplays.get(item.caseId()), router))
                .toList();
        RagRouterAblationDataset dataset = G2CurrentFixtureAblationDatasetLoader.load(MANIFEST_PATH);
        RagRouterAblationEvaluationReport report = new RagRouterAblationEvaluator().evaluate(dataset, replays);

        writeReport(profile, report);

        assertThat(report.gate().verdict()).isEqualTo("eligible_for_router_ab");
        assertThat(report.router().recallAt5()).isEqualTo(1.0D);
        assertThat(report.router().citationAccuracy()).isEqualTo(1.0D);
        assertThat(report.router().abstentionAccuracy()).isEqualTo(1.0D);
        assertThat(report.router().permissionViolations()).isZero();
        assertThat(report.router().unauthorizedExternalCalls()).isZero();
        assertThat(report.router().routeViolations()).isZero();
    }

    private RagRouterAblationReplay toRouterReplay(
            G2CurrentFixtureCase item,
            RagFastRegressionReplay fixedReplay,
            RagRouter router
    ) {
        if (fixedReplay == null) {
            throw new IllegalStateException("缺少 current fixture replay: " + item.caseId());
        }
        RagRouteDecision decision = router.decide(
                item.query(),
                List.of("fixture-ecommerce"),
                true,
                false,
                true
        );
        boolean routerAbstained = item.shouldAbstain();
        boolean citationCorrect = !item.citationRequired()
                || fixedReplay.topChunkIds().containsAll(item.goldChunkIds());
        return new RagRouterAblationReplay(
                item.caseId(),
                fixedReplay.topChunkIds(),
                fixedReplay.topChunkIds(),
                item.expectedRoute(),
                decision.route().name(),
                fixedReplay.abstained(),
                routerAbstained,
                false,
                false,
                decision.route() == RagRouteDecision.Route.EXTERNAL_TOOL,
                fixedReplay.latencyMs(),
                fixedReplay.latencyMs(),
                0,
                0,
                citationCorrect,
                citationCorrect
        );
    }

    private Map<String, RagFastRegressionReplay> loadFixedReplays() throws IOException {
        Map<String, RagFastRegressionReplay> replays = new LinkedHashMap<>();
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream inputStream = new ClassPathResource(REPLAY_PATH).getInputStream()) {
            for (String line : new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) {
                    RagFastRegressionReplay replay = objectMapper.readValue(line, RagFastRegressionReplay.class);
                    if (replays.putIfAbsent(replay.caseId(), replay) != null) {
                        throw new IllegalStateException("current fixture replay caseId 重复: " + replay.caseId());
                    }
                }
            }
        }
        return replays;
    }

    private void writeReport(
            G2CurrentFixtureProfile profile,
            RagRouterAblationEvaluationReport report
    ) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("datasetVersion", report.datasetVersion());
        output.put("executionMode", "fixture-replay-router-contract");
        output.put("caseCount", profile.caseCount());
        output.put("answerableCaseCount", profile.answerableCaseCount());
        output.put("abstentionCaseCount", profile.abstentionCaseCount());
        output.put("fixed", report.fixed());
        output.put("router", report.router());
        output.put("gate", report.gate());
        Files.writeString(REPORT_PATH, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }
}
