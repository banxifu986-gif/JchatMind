package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class MmarcoZhSampledEvaluationRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    void evaluateAndWrite(
            Path rrfOnlyPath,
            Path localRulePath,
            Path teiPath,
            Path reportPath,
            String runId,
            String rerankerModel
    ) throws IOException {
        MmarcoZhSampledEvaluator.VariantRun rrfOnly = readRun(rrfOnlyPath, "rrf-only");
        MmarcoZhSampledEvaluator.VariantRun localRule = readRun(localRulePath, "local-rule-rerank");
        MmarcoZhSampledEvaluator.VariantRun tei = readRun(teiPath, "tei-bge-rerank");
        MmarcoZhSampledEvaluator evaluator = new MmarcoZhSampledEvaluator();
        new MmarcoZhSampledReportWriter().write(
                reportPath,
                runId,
                rerankerModel,
                List.of(rrfOnly, localRule, tei),
                evaluator.compare(localRule, tei)
        );
    }

    private MmarcoZhSampledEvaluator.VariantRun readRun(Path path, String expectedVariant) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalStateException("mMARCO 实验臂 replay 文件不存在: " + path);
        }
        MmarcoZhSampledEvaluator.VariantRun run = OBJECT_MAPPER.readValue(
                Files.readString(path), MmarcoZhSampledEvaluator.VariantRun.class
        );
        if (!expectedVariant.equals(run.variant())) {
            throw new IllegalStateException("mMARCO 实验臂标识不匹配，expected=" + expectedVariant + ", actual=" + run.variant());
        }
        return run;
    }
}
