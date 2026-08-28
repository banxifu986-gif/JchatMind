package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RagIndependentBranchReplayLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> EXPANDED_SOURCES = Set.of("standalone", "llm");

    private RagIndependentBranchReplayLoader() {
    }

    static FrozenReplay load(String manifestPath, String replayPath) throws IOException {
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(manifestPath);
        byte[] manifestBytes = readBytes(manifestPath);
        byte[] replayBytes = readBytes(replayPath);
        Map<String, RagEvaluationCase> casesById = new HashMap<>();
        for (RagEvaluationCase item : dataset.cases()) {
            casesById.put(item.caseId(), item);
        }

        List<QueryReplay> cases = new ArrayList<>();
        Set<String> replayCaseIds = new HashSet<>();
        for (String line : new String(replayBytes, StandardCharsets.UTF_8).split("\\R")) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            RawReplay raw = OBJECT_MAPPER.readValue(line, RawReplay.class);
            RagEvaluationCase evaluationCase = casesById.get(raw.caseId());
            if (evaluationCase == null || !replayCaseIds.add(raw.caseId())) {
                throw new IllegalStateException("三路回放 caseId 无效或重复: " + raw.caseId());
            }
            cases.add(toQueryReplay(raw, evaluationCase));
        }
        if (!replayCaseIds.equals(casesById.keySet())) {
            throw new IllegalStateException("三路回放必须精确覆盖冻结 case 集");
        }
        return new FrozenReplay(
                dataset.manifest().datasetId(),
                List.copyOf(cases),
                sha256(manifestBytes, replayBytes)
        );
    }

    private static QueryReplay toQueryReplay(RawReplay raw, RagEvaluationCase evaluationCase) {
        if (!StringUtils.hasText(raw.originalQuery())
                || !raw.originalQuery().equals(evaluationCase.query())
                || !List.copyOf(raw.conversation()).equals(evaluationCase.conversation())
                || !List.copyOf(raw.kbScope()).equals(evaluationCase.kbScope())) {
            throw new IllegalStateException("三路回放与冻结 case 不一致: " + raw.caseId());
        }
        List<RetrievalQuery> retrievalQueries = validateQueries(raw, evaluationCase.query());
        return new QueryReplay(
                raw.caseId(),
                raw.originalQuery(),
                retrievalQueries,
                List.copyOf(raw.conversation()),
                List.copyOf(raw.kbScope()),
                Set.copyOf(new LinkedHashSet<>(evaluationCase.goldChunkIds())),
                evaluationCase.shouldAbstain()
        );
    }

    private static List<RetrievalQuery> validateQueries(RawReplay raw, String originalQuery) {
        if (raw.retrievalQueries() == null || raw.retrievalQueries().isEmpty()) {
            throw new IllegalStateException("三路回放缺少 retrievalQueries: " + raw.caseId());
        }
        List<RetrievalQuery> queries = List.copyOf(raw.retrievalQueries());
        RetrievalQuery original = queries.get(0);
        if (!"original".equals(original.source()) || !sameText(original.query(), originalQuery)) {
            throw new IllegalStateException("三路回放必须以原问作为首个 query: " + raw.caseId());
        }
        Set<String> uniqueQueries = new HashSet<>();
        for (int index = 0; index < queries.size(); index++) {
            RetrievalQuery query = queries.get(index);
            String normalized = normalize(query.query());
            if (!StringUtils.hasText(query.query()) || !StringUtils.hasText(query.source()) || !uniqueQueries.add(normalized)) {
                throw new IllegalStateException("三路回放 retrieval query 无效或重复: " + raw.caseId());
            }
            if (index > 0 && (!EXPANDED_SOURCES.contains(query.source()) || sameText(query.query(), originalQuery))) {
                throw new IllegalStateException("三路回放扩展 query 来源或文本无效: " + raw.caseId());
            }
        }
        return queries;
    }

    private static boolean sameText(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] readBytes(String path) throws IOException {
        try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private static String sha256(byte[] manifestBytes, byte[] replayBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(manifestBytes);
            digest.update((byte) '\n');
            digest.update(replayBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    record FrozenReplay(String datasetId, List<QueryReplay> cases, String inputSha256) {
        FrozenReplay {
            cases = List.copyOf(cases);
        }
    }

    record QueryReplay(
            String caseId,
            String originalQuery,
            List<RetrievalQuery> retrievalQueries,
            List<RagEvaluationConversationTurn> conversation,
            List<String> kbScope,
            Set<String> goldChunkIds,
            boolean shouldAbstain
    ) {
        QueryReplay {
            retrievalQueries = List.copyOf(retrievalQueries);
            conversation = List.copyOf(conversation);
            kbScope = List.copyOf(kbScope);
            goldChunkIds = Set.copyOf(goldChunkIds);
        }
    }

    record RetrievalQuery(String query, String source) {
    }

    private record RawReplay(
            String caseId,
            String originalQuery,
            List<RetrievalQuery> retrievalQueries,
            List<RagEvaluationConversationTurn> conversation,
            List<String> kbScope,
            JsonNode retrievalContext
    ) {
    }
}
