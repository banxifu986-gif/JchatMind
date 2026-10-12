package com.kama.jchatmind.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LongMemEvalOfficialEvaluatorAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> OFFICIAL_QUESTION_TYPES = Set.of(
            "single-session-user",
            "single-session-assistant",
            "single-session-preference",
            "multi-session",
            "knowledge-update",
            "temporal-reasoning"
    );

    private LongMemEvalOfficialEvaluatorAdapter() {
    }

    static EvaluatorInputs adapt(List<Prediction> predictions, List<Reference> references) {
        if (predictions == null || references == null || references.isEmpty()) {
            throw new IllegalArgumentException("LongMemEval official evaluator input case coverage is empty");
        }

        Map<String, Prediction> predictionById = new LinkedHashMap<>();
        for (Prediction prediction : predictions) {
            if (prediction == null || prediction.questionId() == null || prediction.questionId().isBlank()
                    || prediction.hypothesis() == null || prediction.hypothesis().isBlank()) {
                throw new IllegalArgumentException("LongMemEval official evaluator prediction is incomplete");
            }
            if (predictionById.putIfAbsent(prediction.questionId(), prediction) != null) {
                throw new IllegalArgumentException("LongMemEval official evaluator duplicate prediction question_id");
            }
        }

        Map<String, Reference> referenceById = new LinkedHashMap<>();
        for (Reference reference : references) {
            if (reference == null || reference.questionId() == null || reference.questionId().isBlank()
                    || reference.questionType() == null
                    || !OFFICIAL_QUESTION_TYPES.contains(reference.questionType())
                    || reference.question() == null || reference.question().isBlank()
                    || reference.answer() == null) {
                throw new IllegalArgumentException("LongMemEval official evaluator reference is incomplete");
            }
            if (referenceById.putIfAbsent(reference.questionId(), reference) != null) {
                throw new IllegalArgumentException("LongMemEval official evaluator duplicate reference question_id");
            }
        }

        if (!predictionById.keySet().equals(referenceById.keySet())) {
            throw new IllegalArgumentException("LongMemEval official evaluator case coverage mismatch");
        }

        ArrayNode referencesJson = OBJECT_MAPPER.createArrayNode();
        StringBuilder hypothesesJsonl = new StringBuilder();
        for (Reference reference : references) {
            ObjectNode referenceJson = OBJECT_MAPPER.createObjectNode();
            referenceJson.put("question_id", reference.questionId());
            referenceJson.put("question_type", reference.questionType());
            referenceJson.put("question", reference.question());
            referenceJson.put("answer", reference.answer());
            referencesJson.add(referenceJson);

            Prediction prediction = predictionById.get(reference.questionId());
            ObjectNode hypothesisJson = OBJECT_MAPPER.createObjectNode();
            hypothesisJson.put("question_id", prediction.questionId());
            hypothesisJson.put("hypothesis", prediction.hypothesis());
            hypothesesJsonl.append(writeJson(hypothesisJson)).append('\n');
        }

        return new EvaluatorInputs(hypothesesJsonl.toString(), writeJson(referencesJson));
    }

    private static String writeJson(ObjectNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LongMemEval official evaluator input JSON serialization failed", exception);
        }
    }

    private static String writeJson(ArrayNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LongMemEval official evaluator reference JSON serialization failed", exception);
        }
    }

    record Prediction(String questionId, String hypothesis) {
    }

    record Reference(String questionId, String questionType, String question, String answer) {
    }

    record EvaluatorInputs(String hypothesesJsonl, String referencesJson) {
    }
}
