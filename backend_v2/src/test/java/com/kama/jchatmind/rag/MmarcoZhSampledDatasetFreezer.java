package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class MmarcoZhSampledDatasetFreezer {

    private static final String PREPROCESS_VERSION = "mmarco-zh-sampled-freeze-v1";
    private static final String DEFAULT_DATASET_VERSION = "mmarco-zh-sampled-v1";
    static final String UPSTREAM_REVISION = "6d039c4638c0ba3e46a9cb7b498b145e7edc6230";
    static final String LANGUAGE = "zh";
    static final String MAPPING_VERSION = "mmarco-zh-deterministic-uuid-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Comparator<RunItem> RUN_ITEM_ORDER = Comparator
            .comparingInt(RunItem::rank)
            .thenComparing(RunItem::passageId);

    private final String datasetVersion;

    MmarcoZhSampledDatasetFreezer() {
        this(DEFAULT_DATASET_VERSION);
    }

    MmarcoZhSampledDatasetFreezer(String datasetVersion) {
        if (datasetVersion == null || datasetVersion.isBlank()) {
            throw new IllegalArgumentException("mMARCO datasetVersion 不能为空");
        }
        this.datasetVersion = datasetVersion;
    }

    FrozenDataset freeze(
            List<Passage> passages,
            List<Query> queries,
            Map<String, List<String>> qrelsByQueryId,
            List<RunItem> hardNegativeRun,
            FreezeRequest request
    ) {
        validateRequest(request);
        Map<String, Passage> passagesById = indexPassages(passages);
        Map<String, Query> queriesById = indexQueries(queries);
        Map<String, List<String>> positivesByQueryId = validateQrels(qrelsByQueryId, passagesById, queriesById);
        Map<String, List<RunItem>> hardNegativesByQueryId = validatedHardNegatives(
                hardNegativeRun, positivesByQueryId, passagesById
        );

        List<Query> eligibleQueries = new ArrayList<>();
        for (Query query : queriesById.values().stream().sorted(Comparator.comparing(Query::id)).toList()) {
            if (positivesByQueryId.containsKey(query.id())
                    && !hardNegativesByQueryId.getOrDefault(query.id(), List.of()).isEmpty()) {
                eligibleQueries.add(query);
            }
        }
        if (eligibleQueries.size() < request.totalQueryCount()) {
            throw new IllegalStateException("mMARCO 可冻结 query 数不足: required="
                    + request.totalQueryCount() + ", actual=" + eligibleQueries.size());
        }

        Collections.shuffle(eligibleQueries, new Random(request.randomSeed()));
        List<Query> selectedQueries = List.copyOf(eligibleQueries.subList(0, request.totalQueryCount()));
        List<Query> developmentQueries = List.copyOf(selectedQueries.subList(0, request.developmentQueryCount()));
        List<Query> untouchedTestQueries = List.copyOf(selectedQueries.subList(
                request.developmentQueryCount(), selectedQueries.size()
        ));

        LinkedHashMap<String, Candidate> candidatesByPassageId = new LinkedHashMap<>();
        for (Query query : selectedQueries) {
            for (String passageId : positivesByQueryId.get(query.id())) {
                candidatesByPassageId.putIfAbsent(passageId, candidate(passagesById.get(passageId), "qrels_positive"));
            }
        }
        for (Query query : selectedQueries) {
            hardNegativesByQueryId.get(query.id()).stream()
                    .limit(request.hardNegativesPerQuery())
                    .map(RunItem::passageId)
                    .forEach(passageId -> candidatesByPassageId.putIfAbsent(
                            passageId, candidate(passagesById.get(passageId), "official_hard_negative")
                    ));
        }

        List<Passage> eligibleDistractors = passagesById.values().stream()
                .filter(passage -> !candidatesByPassageId.containsKey(passage.id()))
                .sorted(Comparator.comparing(Passage::id))
                .toList();
        if (eligibleDistractors.size() < request.randomDistractorCount()) {
            throw new IllegalStateException("mMARCO 随机干扰 passage 数不足: required="
                    + request.randomDistractorCount() + ", actual=" + eligibleDistractors.size());
        }
        List<Passage> shuffledDistractors = new ArrayList<>(eligibleDistractors);
        Collections.shuffle(shuffledDistractors, new Random(request.randomSeed() ^ 0x5deece66dL));
        for (Passage distractor : shuffledDistractors.subList(0, request.randomDistractorCount())) {
            candidatesByPassageId.put(distractor.id(), candidate(distractor, "random_distractor"));
        }

        List<Candidate> candidates = candidatesByPassageId.values().stream()
                .sorted(Comparator.comparing(Candidate::passageId))
                .toList();
        Map<String, Candidate> sortedCandidatesByPassageId = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            sortedCandidatesByPassageId.put(candidate.passageId(), candidate);
        }
        return new FrozenDataset(
                developmentQueries,
                untouchedTestQueries,
                candidates,
                Map.copyOf(sortedCandidatesByPassageId),
                freezeGold(selectedQueries, positivesByQueryId)
        );
    }

    FrozenArtifact freezeTo(SourceFiles sourceFiles, FreezeRequest request, Path manifestPath) throws IOException {
        validateSourceFiles(sourceFiles);
        List<Query> queries = readTwoColumnTsv(sourceFiles.queries(), "queries");
        Map<String, List<String>> qrelsByQueryId = readQrels(sourceFiles.qrels());
        Map<String, Query> queriesById = indexQueries(queries);
        Map<String, List<String>> validatedQrels = validateQrelsWithoutCollection(qrelsByQueryId, queriesById);
        Set<String> queriesWithVerifiedHardNegatives = readQueriesWithVerifiedHardNegatives(
                sourceFiles.hardNegativeRun(), validatedQrels
        );
        List<Query> selectedQueries = selectQueries(
                queriesById, validatedQrels, queriesWithVerifiedHardNegatives, request
        );
        Map<String, List<String>> selectedQrels = selectQrels(qrelsByQueryId, selectedQueries);
        List<RunItem> boundedHardNegativeRun = readBoundedSelectedHardNegativeRun(
                sourceFiles.hardNegativeRun(), validatedQrels, selectedQueries, request.hardNegativesPerQuery()
        );
        List<Passage> selectedCollection = scanSelectedCollection(
                sourceFiles.collection(),
                requiredPassageIds(selectedQueries, selectedQrels, boundedHardNegativeRun, request),
                request.randomDistractorCount(),
                request.randomSeed()
        );
        FrozenDataset frozen = freeze(selectedCollection, selectedQueries, selectedQrels, boundedHardNegativeRun, request);
        SourceSha256 sourceSha256 = new SourceSha256(
                sha256(sourceFiles.collection()),
                sha256(sourceFiles.queries()),
                sha256(sourceFiles.qrels()),
                sha256(sourceFiles.hardNegativeRun())
        );
        FrozenManifest manifest = new FrozenManifest(
                datasetVersion,
                PREPROCESS_VERSION,
                UPSTREAM_REVISION,
                LANGUAGE,
                MAPPING_VERSION,
                request,
                sourceSha256,
                frozen.developmentQueries(),
                frozen.untouchedTestQueries(),
                frozen.candidates(),
                frozen.goldLogicalChunkIdsByQueryId()
        );
        Path parent = manifestPath == null ? null : manifestPath.getParent();
        if (manifestPath == null || parent == null) {
            throw new IllegalArgumentException("mMARCO manifest 输出路径无效");
        }
        Files.createDirectories(parent);
        Files.writeString(manifestPath, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        return new FrozenArtifact(frozen, sourceSha256, manifestPath);
    }

    private List<Query> selectQueries(
            Map<String, Query> queriesById,
            Map<String, List<String>> positivesByQueryId,
            Set<String> queriesWithVerifiedHardNegatives,
            FreezeRequest request
    ) {
        validateRequest(request);
        List<Query> eligible = new ArrayList<>();
        for (Query query : queriesById.values().stream().sorted(Comparator.comparing(Query::id)).toList()) {
            if (positivesByQueryId.containsKey(query.id())
                    && queriesWithVerifiedHardNegatives.contains(query.id())) {
                eligible.add(query);
            }
        }
        if (eligible.size() < request.totalQueryCount()) {
            throw new IllegalStateException("mMARCO 可冻结 query 数不足: required="
                    + request.totalQueryCount() + ", actual=" + eligible.size());
        }
        Collections.shuffle(eligible, new Random(request.randomSeed()));
        return List.copyOf(eligible.subList(0, request.totalQueryCount()));
    }

    private Map<String, List<String>> selectQrels(Map<String, List<String>> qrelsByQueryId, List<Query> selectedQueries) {
        Map<String, List<String>> selected = new LinkedHashMap<>();
        for (Query query : selectedQueries) {
            selected.put(query.id(), qrelsByQueryId.get(query.id()));
        }
        return Map.copyOf(selected);
    }

    private Set<String> requiredPassageIds(
            List<Query> selectedQueries,
            Map<String, List<String>> qrelsByQueryId,
            List<RunItem> hardNegativeRun,
            FreezeRequest request
    ) {
        Map<String, List<RunItem>> hardNegativesByQueryId = validatedHardNegativesWithoutCollection(
                hardNegativeRun, qrelsByQueryId
        );
        Set<String> required = new LinkedHashSet<>();
        for (Query query : selectedQueries) {
            required.addAll(qrelsByQueryId.get(query.id()));
            hardNegativesByQueryId.getOrDefault(query.id(), List.of()).stream()
                    .limit(request.hardNegativesPerQuery())
                    .map(RunItem::passageId)
                    .forEach(required::add);
        }
        return Set.copyOf(required);
    }

    private List<Passage> scanSelectedCollection(
            Path collectionPath,
            Set<String> requiredPassageIds,
            int randomDistractorCount,
            long randomSeed
    ) throws IOException {
        Map<String, Passage> requiredPassages = new LinkedHashMap<>();
        List<Passage> reservoir = new ArrayList<>(randomDistractorCount);
        Random random = new Random(randomSeed ^ 0x5deece66dL);
        long eligibleDistractorCount = 0L;
        try (var lines = Files.lines(collectionPath, StandardCharsets.UTF_8)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                Passage passage = parsePassage(iterator.next(), collectionPath);
                if (requiredPassageIds.contains(passage.id())) {
                    if (requiredPassages.putIfAbsent(passage.id(), passage) != null) {
                        throw new IllegalStateException("mMARCO collection 中存在重复的所需 passage ID: " + passage.id());
                    }
                    continue;
                }
                eligibleDistractorCount++;
                if (reservoir.size() < randomDistractorCount) {
                    reservoir.add(passage);
                    continue;
                }
                long replacementIndex = random.nextLong(eligibleDistractorCount);
                if (replacementIndex < randomDistractorCount) {
                    reservoir.set((int) replacementIndex, passage);
                }
            }
        }
        if (!requiredPassages.keySet().containsAll(requiredPassageIds)) {
            Set<String> missing = new LinkedHashSet<>(requiredPassageIds);
            missing.removeAll(requiredPassages.keySet());
            throw new IllegalStateException("mMARCO collection 缺少 qrels 或 hard negative passage: " + missing.stream()
                    .limit(5)
                    .toList());
        }
        if (reservoir.size() < randomDistractorCount) {
            throw new IllegalStateException("mMARCO 随机干扰 passage 数不足: required="
                    + randomDistractorCount + ", actual=" + reservoir.size());
        }
        List<Passage> selected = new ArrayList<>(requiredPassages.values());
        selected.addAll(reservoir);
        return selected;
    }

    private void validateSourceFiles(SourceFiles sourceFiles) {
        if (sourceFiles == null || !isReadableFile(sourceFiles.collection()) || !isReadableFile(sourceFiles.queries())
                || !isReadableFile(sourceFiles.qrels()) || !isReadableFile(sourceFiles.hardNegativeRun())) {
            throw new IllegalArgumentException("mMARCO 冻结输入文件不完整");
        }
    }

    private boolean isReadableFile(Path path) {
        return path != null && Files.isRegularFile(path) && Files.isReadable(path);
    }

    private List<Query> readTwoColumnTsv(Path path, String name) throws IOException {
        List<Query> values = new ArrayList<>();
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String[] fields = splitTsv(iterator.next(), path);
                values.add(new Query(fields[0], fields[1]));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalStateException("mMARCO " + name + " 文件为空: " + path);
        }
        return values;
    }

    private Map<String, List<String>> readQrels(Path path) throws IOException {
        Map<String, List<String>> qrelsByQueryId = new LinkedHashMap<>();
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String[] fields = whitespaceFields(iterator.next(), path, 4);
                int relevance;
                try {
                    relevance = Integer.parseInt(fields[3]);
                } catch (NumberFormatException exception) {
                    throw new IllegalStateException("mMARCO qrels relevance 非整数: " + path, exception);
                }
                if (relevance > 0) {
                    qrelsByQueryId.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(fields[2]);
                }
            }
        }
        if (qrelsByQueryId.isEmpty()) {
            throw new IllegalStateException("mMARCO qrels 不包含正例: " + path);
        }
        return Map.copyOf(qrelsByQueryId);
    }

    private Set<String> readQueriesWithVerifiedHardNegatives(
            Path path,
            Map<String, List<String>> positivesByQueryId
    ) throws IOException {
        Set<String> queryIds = new LinkedHashSet<>();
        boolean hasItems = false;
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                RunItem item = parseRunItem(iterator.next(), path);
                hasItems = true;
                validateHardNegativeRunItem(item, positivesByQueryId);
                if (!positivesByQueryId.get(item.queryId()).contains(item.passageId())) {
                    queryIds.add(item.queryId());
                }
            }
        }
        if (!hasItems) {
            throw new IllegalStateException("mMARCO 官方 hard negative run 为空: " + path);
        }
        return Set.copyOf(queryIds);
    }

    private List<RunItem> readBoundedSelectedHardNegativeRun(
            Path path,
            Map<String, List<String>> positivesByQueryId,
            List<Query> selectedQueries,
            int hardNegativesPerQuery
    ) throws IOException {
        Set<String> selectedQueryIds = selectedQueries.stream().map(Query::id).collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new)
        );
        Map<String, BoundedRunItems> itemsByQueryId = new LinkedHashMap<>();
        boolean hasItems = false;
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                RunItem item = parseRunItem(iterator.next(), path);
                hasItems = true;
                validateHardNegativeRunItem(item, positivesByQueryId);
                if (selectedQueryIds.contains(item.queryId())
                        && !positivesByQueryId.get(item.queryId()).contains(item.passageId())) {
                    itemsByQueryId.computeIfAbsent(item.queryId(), ignored -> new BoundedRunItems())
                            .retain(item, hardNegativesPerQuery);
                }
            }
        }
        if (!hasItems) {
            throw new IllegalStateException("mMARCO 官方 hard negative run 为空: " + path);
        }
        List<RunItem> bounded = new ArrayList<>();
        for (Query query : selectedQueries) {
            BoundedRunItems items = itemsByQueryId.get(query.id());
            if (items != null) {
                bounded.addAll(items.ordered());
            }
        }
        return List.copyOf(bounded);
    }

    private RunItem parseRunItem(String line, Path path) {
        String[] fields = whitespaceFields(line, path, 3);
        String passageId;
        String rankValue;
        if (fields.length >= 4 && ("Q0".equalsIgnoreCase(fields[1]) || "0".equals(fields[1]))) {
            passageId = fields[2];
            rankValue = fields[3];
        } else {
            passageId = fields[1];
            rankValue = fields[2];
        }
        try {
            return new RunItem(fields[0], passageId, Integer.parseInt(rankValue));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("mMARCO hard negative run rank 非整数: " + path, exception);
        }
    }

    private void validateHardNegativeRunItem(
            RunItem item,
            Map<String, List<String>> positivesByQueryId
    ) {
        if (item.queryId() == null || item.queryId().isBlank()
                || item.passageId() == null || item.passageId().isBlank() || item.rank() <= 0
                || !positivesByQueryId.containsKey(item.queryId())) {
            throw new IllegalStateException("mMARCO hard negative run 条目无效");
        }
    }

    private Passage parsePassage(String line, Path path) {
        String[] fields = splitTsv(line, path);
        return new Passage(fields[0], fields[1]);
    }

    private String[] splitTsv(String line, Path path) {
        String[] fields = line == null ? new String[0] : line.strip().split("\\t", 2);
        if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
            throw new IllegalStateException("mMARCO TSV 行格式无效: " + path);
        }
        fields[0] = removeBom(fields[0]);
        return fields;
    }

    private String[] whitespaceFields(String line, Path path, int minimumFields) {
        String[] fields = line == null ? new String[0] : line.strip().split("\\s+");
        if (fields.length < minimumFields || fields[0].isBlank()) {
            throw new IllegalStateException("mMARCO run/qrels 行格式无效: " + path);
        }
        fields[0] = removeBom(fields[0]);
        return fields;
    }

    private String removeBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    String deterministicChunkUuid(String logicalChunkId) {
        if (logicalChunkId == null || logicalChunkId.isBlank()) {
            throw new IllegalArgumentException("mMARCO logical chunk ID 不能为空");
        }
        return UUID.nameUUIDFromBytes(logicalChunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Candidate candidate(Passage passage, String sourceType) {
        String logicalChunkId = "mmarco:zh:" + passage.id();
        return new Candidate(
                passage.id(),
                logicalChunkId,
                deterministicChunkUuid(logicalChunkId),
                passage.content(),
                sourceType
        );
    }

    private Map<String, List<String>> freezeGold(
            List<Query> selectedQueries,
            Map<String, List<String>> positivesByQueryId
    ) {
        Map<String, List<String>> goldByQueryId = new LinkedHashMap<>();
        for (Query query : selectedQueries) {
            goldByQueryId.put(query.id(), positivesByQueryId.get(query.id()).stream()
                    .map(passageId -> "mmarco:zh:" + passageId)
                    .toList());
        }
        return Map.copyOf(goldByQueryId);
    }

    private Map<String, Passage> indexPassages(List<Passage> passages) {
        if (passages == null || passages.isEmpty()) {
            throw new IllegalStateException("mMARCO collection 不能为空");
        }
        Map<String, Passage> passagesById = new LinkedHashMap<>();
        for (Passage passage : passages) {
            if (passage == null || passage.id() == null || passage.id().isBlank()
                    || passage.content() == null || passage.content().isBlank()
                    || passagesById.putIfAbsent(passage.id(), passage) != null) {
                throw new IllegalStateException("mMARCO passage ID 或正文无效/重复");
            }
        }
        return Map.copyOf(passagesById);
    }

    private Map<String, Query> indexQueries(List<Query> queries) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalStateException("mMARCO queries 不能为空");
        }
        Map<String, Query> queriesById = new LinkedHashMap<>();
        for (Query query : queries) {
            if (query == null || query.id() == null || query.id().isBlank()
                    || query.text() == null || query.text().isBlank()
                    || queriesById.putIfAbsent(query.id(), query) != null) {
                throw new IllegalStateException("mMARCO query ID 或正文无效/重复");
            }
        }
        return Map.copyOf(queriesById);
    }

    private Map<String, List<String>> validateQrels(
            Map<String, List<String>> qrelsByQueryId,
            Map<String, Passage> passagesById,
            Map<String, Query> queriesById
    ) {
        Map<String, List<String>> validated = validateQrelsWithoutCollection(qrelsByQueryId, queriesById);
        if (validated.values().stream().flatMap(List::stream).anyMatch(passageId -> !passagesById.containsKey(passageId))) {
            throw new IllegalStateException("mMARCO qrels passage 不在 collection 中");
        }
        return validated;
    }

    private Map<String, List<RunItem>> validatedHardNegatives(
            List<RunItem> hardNegativeRun,
            Map<String, List<String>> positivesByQueryId,
            Map<String, Passage> passagesById
    ) {
        Map<String, List<RunItem>> validated = validatedHardNegativesWithoutCollection(hardNegativeRun, positivesByQueryId);
        if (validated.values().stream().flatMap(List::stream)
                .anyMatch(item -> !passagesById.containsKey(item.passageId()))) {
            throw new IllegalStateException("mMARCO hard negative run passage 不在 collection 中");
        }
        return validated;
    }

    private Map<String, List<String>> validateQrelsWithoutCollection(
            Map<String, List<String>> qrelsByQueryId,
            Map<String, Query> queriesById
    ) {
        if (qrelsByQueryId == null || qrelsByQueryId.isEmpty()) {
            throw new IllegalStateException("mMARCO qrels 不能为空");
        }
        Map<String, List<String>> validated = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : qrelsByQueryId.entrySet()) {
            String queryId = entry.getKey();
            if (!queriesById.containsKey(queryId) || entry.getValue() == null || entry.getValue().isEmpty()) {
                throw new IllegalStateException("mMARCO qrels query 无效: " + queryId);
            }
            Set<String> uniquePassages = new LinkedHashSet<>(entry.getValue());
            if (uniquePassages.size() != entry.getValue().size() || uniquePassages.stream().anyMatch(
                    passageId -> passageId == null || passageId.isBlank()
            )) {
                throw new IllegalStateException("mMARCO qrels passage 无效: " + queryId);
            }
            validated.put(queryId, uniquePassages.stream().sorted().toList());
        }
        return Map.copyOf(validated);
    }

    private Map<String, List<RunItem>> validatedHardNegativesWithoutCollection(
            List<RunItem> hardNegativeRun,
            Map<String, List<String>> positivesByQueryId
    ) {
        if (hardNegativeRun == null || hardNegativeRun.isEmpty()) {
            throw new IllegalStateException("mMARCO 官方 hard negative run 不能为空");
        }
        Map<String, List<RunItem>> byQueryId = new LinkedHashMap<>();
        for (RunItem item : hardNegativeRun) {
            if (item == null || item.queryId() == null || item.queryId().isBlank()
                    || item.passageId() == null || item.passageId().isBlank() || item.rank() <= 0
                    || !positivesByQueryId.containsKey(item.queryId())) {
                throw new IllegalStateException("mMARCO hard negative run 条目无效");
            }
            if (!positivesByQueryId.get(item.queryId()).contains(item.passageId())) {
                byQueryId.computeIfAbsent(item.queryId(), ignored -> new ArrayList<>()).add(item);
            }
        }
        Map<String, List<RunItem>> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, List<RunItem>> entry : byQueryId.entrySet()) {
            List<RunItem> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(RunItem::rank).thenComparing(RunItem::passageId))
                    .toList();
            Set<String> uniquePassageIds = new LinkedHashSet<>();
            List<RunItem> deduplicated = ordered.stream()
                    .filter(item -> uniquePassageIds.add(item.passageId()))
                    .toList();
            sorted.put(entry.getKey(), deduplicated);
        }
        return Map.copyOf(sorted);
    }

    private void validateRequest(FreezeRequest request) {
        if (request == null || request.totalQueryCount() <= 0 || request.developmentQueryCount() <= 0
                || request.developmentQueryCount() >= request.totalQueryCount()
                || request.hardNegativesPerQuery() <= 0 || request.randomDistractorCount() <= 0) {
            throw new IllegalArgumentException("mMARCO freeze 参数无效");
        }
    }

    private static final class BoundedRunItems {

        private final Map<String, RunItem> byPassageId = new LinkedHashMap<>();
        private final TreeSet<RunItem> ordered = new TreeSet<>(RUN_ITEM_ORDER);

        private void retain(RunItem item, int limit) {
            RunItem existing = byPassageId.get(item.passageId());
            if (existing != null) {
                if (RUN_ITEM_ORDER.compare(item, existing) < 0) {
                    ordered.remove(existing);
                    ordered.add(item);
                    byPassageId.put(item.passageId(), item);
                }
                return;
            }
            if (ordered.size() < limit) {
                ordered.add(item);
                byPassageId.put(item.passageId(), item);
                return;
            }
            RunItem worst = ordered.last();
            if (RUN_ITEM_ORDER.compare(item, worst) < 0) {
                ordered.remove(worst);
                byPassageId.remove(worst.passageId());
                ordered.add(item);
                byPassageId.put(item.passageId(), item);
            }
        }

        private List<RunItem> ordered() {
            return List.copyOf(ordered);
        }
    }

    record Passage(String id, String content) {
    }

    record Query(String id, String text) {
    }

    record RunItem(String queryId, String passageId, int rank) {
    }

    record FreezeRequest(
            int totalQueryCount,
            int developmentQueryCount,
            int hardNegativesPerQuery,
            int randomDistractorCount,
            long randomSeed
    ) {
    }

    record Candidate(
            String passageId,
            String logicalChunkId,
            String runtimeChunkUuid,
            String content,
            String sourceType
    ) {
    }

    record FrozenDataset(
            List<Query> developmentQueries,
            List<Query> untouchedTestQueries,
            List<Candidate> candidates,
            Map<String, Candidate> candidateByPassageId,
            Map<String, List<String>> goldLogicalChunkIdsByQueryId
    ) {
    }

    record SourceFiles(Path collection, Path queries, Path qrels, Path hardNegativeRun) {
    }

    record SourceSha256(String collection, String queries, String qrels, String hardNegativeRun) {
    }

    record FrozenArtifact(FrozenDataset dataset, SourceSha256 sourceSha256, Path manifestPath) {
    }

    record FrozenManifest(
            String datasetVersion,
            String preprocessVersion,
            String upstreamRevision,
            String language,
            String mappingVersion,
            FreezeRequest freezeRequest,
            SourceSha256 sourceSha256,
            List<Query> developmentQueries,
            List<Query> untouchedTestQueries,
            List<Candidate> candidates,
            Map<String, List<String>> goldLogicalChunkIdsByQueryId
    ) {
    }
}
