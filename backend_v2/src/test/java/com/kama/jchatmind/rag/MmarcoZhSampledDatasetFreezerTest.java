package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmarcoZhSampledDatasetFreezerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void freezesDisjointQuerySplitsCandidatesAndDeterministicChunkUuids() {
        MmarcoZhSampledDatasetFreezer freezer = new MmarcoZhSampledDatasetFreezer();

        MmarcoZhSampledDatasetFreezer.FrozenDataset frozen = freezer.freeze(
                passages(),
                queries(),
                qrels(),
                hardNegativeRun(),
                new MmarcoZhSampledDatasetFreezer.FreezeRequest(4, 2, 2, 1, 20260825L)
        );

        assertEquals(2, frozen.developmentQueries().size());
        assertEquals(2, frozen.untouchedTestQueries().size());
        assertTrue(frozen.developmentQueries().stream()
                .noneMatch(query -> frozen.untouchedTestQueries().contains(query)));
        assertEquals(7, frozen.candidates().size());
        assertEquals("mmarco:zh:p-1", frozen.candidateByPassageId().get("p-1").logicalChunkId());
        assertEquals(
                frozen.candidateByPassageId().get("p-1").runtimeChunkUuid(),
                freezer.deterministicChunkUuid("mmarco:zh:p-1")
        );
        assertTrue(frozen.candidates().stream().anyMatch(candidate -> candidate.sourceType().equals("qrels_positive")));
        assertTrue(frozen.candidates().stream().anyMatch(candidate -> candidate.sourceType().equals("official_hard_negative")));
        assertTrue(frozen.candidates().stream().anyMatch(candidate -> candidate.sourceType().equals("random_distractor")));
    }

    @Test
    void rejectsFreezeWhenTooFewQueriesHaveVerifiedHardNegatives() {
        MmarcoZhSampledDatasetFreezer freezer = new MmarcoZhSampledDatasetFreezer();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> freezer.freeze(
                passages(),
                queries(),
                qrels(),
                List.of(new MmarcoZhSampledDatasetFreezer.RunItem("q-1", "p-3", 1)),
                new MmarcoZhSampledDatasetFreezer.FreezeRequest(4, 2, 2, 1, 20260825L)
        ));

        assertTrue(exception.getMessage().contains("可冻结 query 数不足"));
    }

    @Test
    void skipsQrelsQueriesWithoutOfficialHardNegativesWhenEnoughVerifiedQueriesRemain() {
        MmarcoZhSampledDatasetFreezer freezer = new MmarcoZhSampledDatasetFreezer();
        List<MmarcoZhSampledDatasetFreezer.Query> queries = new java.util.ArrayList<>(queries());
        queries.add(new MmarcoZhSampledDatasetFreezer.Query("q-without-run", "query without official run"));
        Map<String, List<String>> qrels = new java.util.LinkedHashMap<>(qrels());
        qrels.put("q-without-run", List.of("p-1"));

        MmarcoZhSampledDatasetFreezer.FrozenDataset frozen = freezer.freeze(
                passages(),
                queries,
                qrels,
                hardNegativeRun(),
                new MmarcoZhSampledDatasetFreezer.FreezeRequest(4, 2, 2, 1, 20260825L)
        );

        assertEquals(4, frozen.developmentQueries().size() + frozen.untouchedTestQueries().size());
        assertTrue(frozen.developmentQueries().stream()
                .noneMatch(query -> "q-without-run".equals(query.id())));
        assertTrue(frozen.untouchedTestQueries().stream()
                .noneMatch(query -> "q-without-run".equals(query.id())));
    }

    @Test
    void readsFrozenTsvInputsRecordsTheirHashesAndWritesOneManifest() throws Exception {
        Path collection = write("collection.tsv", """
                p-1\tpositive one
                p-2\tpositive two
                p-3\tnegative three
                p-4\tnegative four
                p-5\tnegative five
                p-6\tnegative six
                p-7\trandom distractor
                """);
        Path queries = write("queries.tsv", """
                q-1\tquery one
                q-2\tquery two
                q-3\tquery three
                q-4\tquery four
                """);
        Path qrels = write("qrels.tsv", """
                q-1 0 p-1 1
                q-2 0 p-2 1
                q-3 0 p-1 1
                q-4 0 p-2 1
                """);
        Path run = write("official.run", """
                q-1 Q0 p-3 1 1.0 official
                q-1 Q0 p-not-selected 2 0.9 official
                q-2 Q0 p-4 1 1.0 official
                q-3 Q0 p-5 1 1.0 official
                q-4 Q0 p-6 1 1.0 official
                """);
        Path manifest = temporaryDirectory.resolve("mmarco-zh-sampled-v1.json");
        MmarcoZhSampledDatasetFreezer freezer = new MmarcoZhSampledDatasetFreezer();

        MmarcoZhSampledDatasetFreezer.FrozenArtifact artifact = freezer.freezeTo(
                new MmarcoZhSampledDatasetFreezer.SourceFiles(collection, queries, qrels, run),
                new MmarcoZhSampledDatasetFreezer.FreezeRequest(4, 2, 1, 1, 20260825L),
                manifest
        );

        JsonNode root = new ObjectMapper().readTree(manifest.toFile());
        assertEquals("mmarco-zh-sampled-v1", root.path("datasetVersion").asText());
        assertEquals("mmarco-zh-sampled-freeze-v1", root.path("preprocessVersion").asText());
        assertEquals("zh", root.path("language").asText());
        assertEquals("6d039c4638c0ba3e46a9cb7b498b145e7edc6230", root.path("upstreamRevision").asText());
        assertEquals("mmarco-zh-deterministic-uuid-v1", root.path("mappingVersion").asText());
        assertEquals(artifact.sourceSha256().collection(), root.path("sourceSha256").path("collection").asText());
        assertEquals(7, root.path("candidates").size());
        assertEquals("mmarco:zh:p-1", root.path("candidates").get(0).path("logicalChunkId").asText());
    }

    @Test
    void writesTheCallerSelectedDatasetVersionToTheManifest() throws Exception {
        Path collection = write("versioned-collection.tsv", """
                p-1\tpositive one
                p-2\tnegative two
                p-3\tpositive three
                p-4\tnegative four
                p-5\trandom five
                """);
        Path queries = write("versioned-queries.tsv", "q-1\tquery one\nq-2\tquery two");
        Path qrels = write("versioned-qrels.tsv", "q-1 0 p-1 1\nq-2 0 p-3 1");
        Path run = write("versioned-run.tsv", "q-1 Q0 p-2 1 1.0 official\nq-2 Q0 p-4 1 1.0 official");
        Path manifest = temporaryDirectory.resolve("mmarco-zh-sampled-v2.json");

        new MmarcoZhSampledDatasetFreezer("mmarco-zh-sampled-v2").freezeTo(
                new MmarcoZhSampledDatasetFreezer.SourceFiles(collection, queries, qrels, run),
                new MmarcoZhSampledDatasetFreezer.FreezeRequest(2, 1, 1, 1, 20260825L),
                manifest
        );

        assertEquals("mmarco-zh-sampled-v2", new ObjectMapper().readTree(manifest.toFile())
                .path("datasetVersion").asText());
    }

    @Test
    void streamsTheOfficialRunInsteadOfMaterializingEveryRunItem() {
        boolean materializesTheWholeRun = java.util.Arrays.stream(MmarcoZhSampledDatasetFreezer.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("readHardNegativeRun")
                        && method.getReturnType().equals(List.class)
                        && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{Path.class}));

        assertTrue(!materializesTheWholeRun);
    }

    private Path write(String filename, String content) throws Exception {
        Path path = temporaryDirectory.resolve(filename);
        Files.writeString(path, content.strip());
        return path;
    }

    private List<MmarcoZhSampledDatasetFreezer.Passage> passages() {
        return List.of(
                new MmarcoZhSampledDatasetFreezer.Passage("p-1", "positive one"),
                new MmarcoZhSampledDatasetFreezer.Passage("p-2", "positive two"),
                new MmarcoZhSampledDatasetFreezer.Passage("p-3", "negative three"),
                new MmarcoZhSampledDatasetFreezer.Passage("p-4", "negative four"),
                new MmarcoZhSampledDatasetFreezer.Passage("p-5", "negative five"),
                new MmarcoZhSampledDatasetFreezer.Passage("p-6", "negative six"),
                new MmarcoZhSampledDatasetFreezer.Passage("p-7", "random distractor")
        );
    }

    private List<MmarcoZhSampledDatasetFreezer.Query> queries() {
        return List.of(
                new MmarcoZhSampledDatasetFreezer.Query("q-1", "query one"),
                new MmarcoZhSampledDatasetFreezer.Query("q-2", "query two"),
                new MmarcoZhSampledDatasetFreezer.Query("q-3", "query three"),
                new MmarcoZhSampledDatasetFreezer.Query("q-4", "query four")
        );
    }

    private Map<String, List<String>> qrels() {
        return Map.of(
                "q-1", List.of("p-1"),
                "q-2", List.of("p-2"),
                "q-3", List.of("p-1"),
                "q-4", List.of("p-2")
        );
    }

    private List<MmarcoZhSampledDatasetFreezer.RunItem> hardNegativeRun() {
        return List.of(
                new MmarcoZhSampledDatasetFreezer.RunItem("q-1", "p-3", 1),
                new MmarcoZhSampledDatasetFreezer.RunItem("q-2", "p-4", 1),
                new MmarcoZhSampledDatasetFreezer.RunItem("q-3", "p-5", 1),
                new MmarcoZhSampledDatasetFreezer.RunItem("q-4", "p-6", 1)
        );
    }
}
