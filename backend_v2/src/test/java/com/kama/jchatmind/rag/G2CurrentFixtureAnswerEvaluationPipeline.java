package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

final class G2CurrentFixtureAnswerEvaluationPipeline {

    Report evaluate(
            G2CurrentFixtureProfile profile,
            G2CurrentFixtureRuntimeCorpus fixture,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            Map<String, List<RagRetrievalResult>> fixedResultsByCaseId,
            Map<String, List<RagRetrievalResult>> routerResultsByCaseId,
            ChatClient generatorClient,
            String generatorModel,
            ChatClient judgeClient,
            String judgeModel
    ) {
        if (judgeClient != null && generatorClient == null) {
            throw new IllegalArgumentException("answer generator is required when a judge is enabled");
        }
        if (generatorClient != null && !StringUtils.hasText(generatorModel)) {
            throw new IllegalArgumentException("answer generator model name is required");
        }
        if (judgeClient != null && !StringUtils.hasText(judgeModel)) {
            throw new IllegalArgumentException("answer quality judge model name is required");
        }

        G2CurrentFixtureAnswerContextBuilder builder = new G2CurrentFixtureAnswerContextBuilder();
        G2CurrentFixtureAnswerContextBuilder.AnswerInputs fixedInputs =
                builder.build(profile, fixedResultsByCaseId);
        G2CurrentFixtureAnswerContextBuilder.AnswerInputs routerInputs =
                builder.build(profile, routerResultsByCaseId);
        G2CurrentFixtureAnswerGenerationEvaluator generationEvaluator =
                new G2CurrentFixtureAnswerGenerationEvaluator(generatorClient, generatorModel);
        G2CurrentFixtureAnswerGenerationEvaluator.Report fixedGeneration =
                generationEvaluator.generate(profile, fixedInputs.contextsByCaseId());
        G2CurrentFixtureAnswerGenerationEvaluator.Report routerGeneration =
                generationEvaluator.generate(profile, routerInputs.contextsByCaseId());
        G2CurrentFixtureAnswerCitationEvaluator citationEvaluator =
                new G2CurrentFixtureAnswerCitationEvaluator();
        G2CurrentFixtureAnswerCitationEvaluator.Report fixedCitation = citationEvaluator.evaluate(
                fixture, plan.logicalChunkIdByRuntimeUuid(), fixedInputs.retrievedRuntimeChunkIdsByCaseId(),
                fixedGeneration.finalAnswersByCaseId()
        );
        G2CurrentFixtureAnswerCitationEvaluator.Report routerCitation = citationEvaluator.evaluate(
                fixture, plan.logicalChunkIdByRuntimeUuid(), routerInputs.retrievedRuntimeChunkIdsByCaseId(),
                routerGeneration.finalAnswersByCaseId()
        );
        G2CurrentFixtureAnswerQualityReport fixedQuality =
                new G2CurrentFixtureAnswerQualityEvaluator(judgeClient, judgeModel)
                        .evaluate(profile, fixedInputs.contextsByCaseId(), fixedGeneration.finalAnswersByCaseId());
        G2CurrentFixtureAnswerQualityReport routerQuality =
                new G2CurrentFixtureAnswerQualityEvaluator(judgeClient, judgeModel)
                        .evaluate(profile, routerInputs.contextsByCaseId(), routerGeneration.finalAnswersByCaseId());
        G2CurrentFixtureAnswerQualityComparisonEvaluator.Report pairedQuality = judgeClient == null
                ? null
                : new G2CurrentFixtureAnswerQualityComparisonEvaluator().compare(fixedQuality, routerQuality);
        G2CurrentFixtureRefusalEvaluator.Report fixedRefusal =
                new G2CurrentFixtureRefusalEvaluator(judgeClient, judgeModel)
                        .evaluate(profile, fixedInputs.contextsByCaseId(), fixedGeneration.finalAnswersByCaseId());
        G2CurrentFixtureRefusalEvaluator.Report routerRefusal =
                new G2CurrentFixtureRefusalEvaluator(judgeClient, judgeModel)
                        .evaluate(profile, routerInputs.contextsByCaseId(), routerGeneration.finalAnswersByCaseId());
        G2CurrentFixtureRefusalComparisonEvaluator.Report pairedRefusal = judgeClient == null
                ? null
                : new G2CurrentFixtureRefusalComparisonEvaluator().compare(fixedRefusal, routerRefusal);
        boolean completeGeneration = "complete".equals(fixedGeneration.status())
                && "complete".equals(routerGeneration.status());
        boolean answerQualityMeasured = pairedQuality != null
                && "complete".equals(pairedQuality.status())
                && pairedQuality.pairedCaseCount() == profile.answerableCaseCount();
        boolean refusalMeasured = pairedRefusal != null
                && "complete".equals(pairedRefusal.status())
                && pairedRefusal.pairedCaseCount() == profile.abstentionCaseCount();
        return new Report(
                generatorClient != null,
                generatorModel,
                judgeClient != null,
                judgeModel,
                completeGeneration,
                answerQualityMeasured,
                refusalMeasured,
                fixedGeneration,
                routerGeneration,
                fixedCitation,
                routerCitation,
                fixedQuality,
                routerQuality,
                pairedQuality,
                fixedRefusal,
                routerRefusal,
                pairedRefusal
        );
    }

    record Report(
            boolean answerGenerationEnabled,
            String generatorModel,
            boolean answerQualityJudgeEnabled,
            String judgeModel,
            boolean answerCitationAccuracyMeasured,
            boolean answerQualityMeasured,
            boolean answerRefusalMeasured,
            G2CurrentFixtureAnswerGenerationEvaluator.Report fixedGeneration,
            G2CurrentFixtureAnswerGenerationEvaluator.Report routerGeneration,
            G2CurrentFixtureAnswerCitationEvaluator.Report fixedCitation,
            G2CurrentFixtureAnswerCitationEvaluator.Report routerCitation,
            G2CurrentFixtureAnswerQualityReport fixedQuality,
            G2CurrentFixtureAnswerQualityReport routerQuality,
            G2CurrentFixtureAnswerQualityComparisonEvaluator.Report pairedQuality,
            G2CurrentFixtureRefusalEvaluator.Report fixedRefusal,
            G2CurrentFixtureRefusalEvaluator.Report routerRefusal,
            G2CurrentFixtureRefusalComparisonEvaluator.Report pairedRefusal
    ) {
    }
}
