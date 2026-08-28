package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "rag.eval.mmarco.freeze.enabled", matches = "true")
class MmarcoZhSampledDatasetFreezeRuntimeTest {

    private static final String DATASET_VERSION = "mmarco-zh-sampled-v3-local-diagnostic";
    private static final Path EVALUATION_DIRECTORY = Path.of(
            "target", "rag-eval", "external", DATASET_VERSION
    );
    private static final Path INPUT_DIRECTORY = Path.of(
            "target", "rag-eval", "external", "mmarco-zh-sampled-v1", "input"
    );

    @Test
    void freezesVerifiedLocalMmarcoZhInputs() throws Exception {
        MmarcoZhSampledDatasetFreezer.FrozenArtifact frozen = new MmarcoZhSampledDatasetFreezer(DATASET_VERSION).freezeTo(
                new MmarcoZhSampledDatasetFreezer.SourceFiles(
                        INPUT_DIRECTORY.resolve("collection.tsv"),
                        INPUT_DIRECTORY.resolve("queries.tsv"),
                        INPUT_DIRECTORY.resolve("qrels.tsv"),
                        INPUT_DIRECTORY.resolve("run.bm25.tsv")
                ),
                new MmarcoZhSampledDatasetFreezer.FreezeRequest(300, 200, 1, 500, 20_260_825L),
                EVALUATION_DIRECTORY.resolve(DATASET_VERSION + "-manifest.json")
        );

        assertEquals(200, frozen.dataset().developmentQueries().size());
        assertEquals(100, frozen.dataset().untouchedTestQueries().size());
        assertEquals(1_116, frozen.dataset().candidates().size());
        Set<String> queryIds = new LinkedHashSet<>();
        frozen.dataset().developmentQueries().forEach(query -> assertTrue(queryIds.add(query.id())));
        frozen.dataset().untouchedTestQueries().forEach(query -> assertTrue(queryIds.add(query.id())));
    }
}
