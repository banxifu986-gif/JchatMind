package com.kama.jchatmind.intent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;
import java.util.UUID;

public class IntentFallbackRouter {

    private static final Set<BusinessIntent> SIDE_EFFECT_INTENTS = Set.of(
            BusinessIntent.SEND_EMAIL,
            BusinessIntent.FILE_MUTATION,
            BusinessIntent.COMPOSITE
    );
    private static final int MAX_ENTITY_COUNT = 16;
    private static final int MAX_ENTITY_VALUE_LENGTH = 256;
    private static final int MAX_RETRIES = 2;

    private final IntentRouter ruleRouter;
    private final ObjectMapper objectMapper;
    private final IntentModelClient modelClient;
    private final long timeoutMillis;
    private final double modelMinConfidence;
    private final double minMargin;
    private final int maxInputCharacters;
    private final int maxOutputCharacters;
    private final int maxRetries;
    private final boolean shadowMode;
    private final IntentDecisionTelemetry telemetry;
    private final IntentRoutingProperties routingProperties;

    public IntentFallbackRouter(
            IntentRouter ruleRouter,
            ObjectMapper objectMapper,
            IntentModelClient modelClient,
            IntentRoutingProperties routingProperties,
            boolean shadowMode,
            IntentDecisionTelemetry telemetry
    ) {
        if (ruleRouter == null || objectMapper == null || routingProperties == null) {
            throw new IllegalArgumentException("ruleRouter, objectMapper and routingProperties must not be null");
        }
        if (routingProperties.getTimeoutMillis() <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (Double.isNaN(routingProperties.getMinConfidence())
                || routingProperties.getMinConfidence() < 0D
                || routingProperties.getMinConfidence() > 1D) {
            throw new IllegalArgumentException("modelMinConfidence must be between 0 and 1");
        }
        if (Double.isNaN(routingProperties.getMinMargin())
                || routingProperties.getMinMargin() < 0D
                || routingProperties.getMinMargin() > 1D) {
            throw new IllegalArgumentException("minMargin must be between 0 and 1");
        }
        if (Double.isNaN(routingProperties.getMinMargin())
                || routingProperties.getMinMargin() < 0D
                || routingProperties.getMinMargin() > 1D) {
            throw new IllegalArgumentException("minMargin must be between 0 and 1");
        }
        if (routingProperties.getMaxInputCharacters() <= 0
                || routingProperties.getMaxOutputCharacters() <= 0) {
            throw new IllegalArgumentException("model input and output character limits must be positive");
        }
        if (routingProperties.getMaxRetries() < 0 || routingProperties.getMaxRetries() > MAX_RETRIES) {
            throw new IllegalArgumentException("maxRetries must be between 0 and " + MAX_RETRIES);
        }
        this.ruleRouter = ruleRouter;
        this.objectMapper = objectMapper;
        this.modelClient = modelClient;
        this.timeoutMillis = routingProperties.getTimeoutMillis();
        this.modelMinConfidence = routingProperties.getMinConfidence();
        this.minMargin = routingProperties.getMinMargin();
        this.maxInputCharacters = routingProperties.getMaxInputCharacters();
        this.maxOutputCharacters = routingProperties.getMaxOutputCharacters();
        this.maxRetries = routingProperties.getMaxRetries();
        this.shadowMode = shadowMode;
        this.telemetry = telemetry == null ? IntentDecisionTelemetry.noop() : telemetry;
        this.routingProperties = copyRoutingProperties(routingProperties);
        this.routingProperties.setLlmEnabled(modelClient != null && !shadowMode);
        this.routingProperties.setShadowEnabled(modelClient != null && shadowMode);
    }

    IntentRoutingProperties routingPropertiesSnapshot() {
        return copyRoutingProperties(routingProperties);
    }

    private IntentRoutingProperties copyRoutingProperties(IntentRoutingProperties source) {
        IntentRoutingProperties copy = new IntentRoutingProperties();
        copy.setLlmEnabled(source.isLlmEnabled());
        copy.setShadowEnabled(source.isShadowEnabled());
        copy.setModel(source.getModel());
        copy.setModelVersion(source.getModelVersion());
        copy.setTimeoutMillis(source.getTimeoutMillis());
        copy.setMinConfidence(source.getMinConfidence());
        copy.setMinMargin(source.getMinMargin());
        copy.setMaxInputCharacters(source.getMaxInputCharacters());
        copy.setMaxOutputCharacters(source.getMaxOutputCharacters());
        copy.setMaxRetries(source.getMaxRetries());
        copy.setMaxTelemetryRecords(source.getMaxTelemetryRecords());
        return copy;
    }

    public IntentDecision decide(String query) {
        return decide(query, null, null, null);
    }

    public IntentDecision decide(String query, String previousUserQuery) {
        return decide(query, previousUserQuery, null, null);
    }

    public IntentDecision decide(
            String query,
            String previousUserQuery,
            String traceId,
            String correlationId
    ) {
        IntentDecision ruleDecision = ruleRouter.decide(query, previousUserQuery);
        if (!shouldUseModel(ruleDecision)) {
            return ruleDecision;
        }

        IntentDecision candidateDecision;
        if (query != null && query.length() > maxInputCharacters) {
            candidateDecision = safeClarify("LLM_INPUT_TOO_LONG", IntentRiskLevel.NONE);
        } else {
            candidateDecision = classifyWithRetries(query);
        }
        String telemetryInput = previousUserQuery != null && !previousUserQuery.isBlank()
                ? previousUserQuery + "\n" + query
                : query;
        recordTelemetry(telemetryInput, traceId, correlationId, ruleDecision, candidateDecision);
        return shadowMode ? ruleDecision : candidateDecision;
    }

    private IntentDecision classifyWithRetries(String query) {
        IntentDecision candidateDecision = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String response = CompletableFuture
                        .supplyAsync(() -> modelClient.classify(query))
                        .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                        .join();
                candidateDecision = response != null && response.length() > maxOutputCharacters
                        ? safeClarify("LLM_OUTPUT_TOO_LONG", IntentRiskLevel.NONE)
                        : parseModelDecision(response);
            } catch (RuntimeException e) {
                candidateDecision = safeClarify("LLM_FALLBACK_FAILED", IntentRiskLevel.NONE);
            }
            if (attempt == maxRetries || !isRetryable(candidateDecision.reasonCode())) {
                return candidateDecision;
            }
        }
        return safeClarify("LLM_FALLBACK_FAILED", IntentRiskLevel.NONE);
    }

    private boolean isRetryable(String reasonCode) {
        return "LLM_OUTPUT_EMPTY".equals(reasonCode)
                || "LLM_OUTPUT_INVALID".equals(reasonCode)
                || "LLM_LOW_CONFIDENCE".equals(reasonCode)
                || "LLM_OUTPUT_TOO_LONG".equals(reasonCode)
                || "LLM_FALLBACK_FAILED".equals(reasonCode);
    }

    private boolean shouldUseModel(IntentDecision ruleDecision) {
        return modelClient != null
                && (shadowMode || !ruleDecision.requiresClarification())
                && ruleDecision.confidence() < modelMinConfidence
                && ruleDecision.route() != IntentRoute.ABSTAIN
                && ruleDecision.riskLevel() != IntentRiskLevel.SIDE_EFFECT
                && ruleDecision.riskLevel() != IntentRiskLevel.BLOCKED;
    }

    private void recordTelemetry(
            String query,
            String traceId,
            String correlationId,
            IntentDecision ruleDecision,
            IntentDecision candidateDecision
    ) {
        try {
            telemetry.record(new IntentDecisionEvent(
                    Instant.now(),
                    traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId,
                    correlationId == null || correlationId.isBlank()
                            ? UUID.randomUUID().toString()
                            : correlationId,
                    hash(query),
                    candidateDecision.decisionVersion(),
                    candidateDecision.policyVersion(),
                    shadowMode,
                    ruleDecision.businessIntent(),
                    ruleDecision.route(),
                    candidateDecision.businessIntent(),
                    candidateDecision.route(),
                    candidateDecision.riskLevel(),
                    candidateDecision.decisionSource(),
                    candidateDecision.confidence(),
                    candidateDecision.confidenceMargin(),
                    minMargin,
                    candidateDecision.candidateCapabilities(),
                    candidateDecision.reasonCode()
            ));
        } catch (RuntimeException ignored) {
            // Telemetry must never affect intent routing or tool safety.
        }
    }

    private String hash(String query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((query == null ? "" : query).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private IntentDecision parseModelDecision(String response) {
        if (response == null || response.isBlank()) {
            return safeClarify("LLM_OUTPUT_EMPTY", IntentRiskLevel.NONE);
        }

        try {
            JsonNode root = objectMapper.readTree(response);
            if (root == null || !root.isObject()) {
                return safeClarify("LLM_OUTPUT_INVALID", IntentRiskLevel.NONE);
            }

            BusinessIntent businessIntent = parseBusinessIntent(root.path("businessIntent"));
            double confidence = parseConfidence(root.path("confidence"));
            double margin = parseMargin(root.path("margin"));
            if (margin > confidence) {
                return safeClarify("LLM_OUTPUT_INVALID", IntentRiskLevel.NONE, confidence, margin);
            }
            if (confidence < modelMinConfidence) {
                return safeClarify("LLM_LOW_CONFIDENCE", IntentRiskLevel.NONE, confidence, margin);
            }
            if (margin < minMargin) {
                return safeClarify("LLM_LOW_MARGIN", IntentRiskLevel.NONE, confidence, margin);
            }
            if (root.path("requiresClarification").asBoolean(false)) {
                return safeClarify("LLM_REQUESTED_CLARIFICATION", IntentRiskLevel.NONE, confidence, margin);
            }

            QueryIntent queryIntent = parseQueryIntent(root.path("queryIntent"));
            Map<String, String> entities = parseEntities(root.path("entities"));
            return mapModelIntent(businessIntent, queryIntent, confidence, margin, entities);
        } catch (RuntimeException | JsonProcessingException e) {
            return safeClarify("LLM_OUTPUT_INVALID", IntentRiskLevel.NONE);
        }
    }

    private BusinessIntent parseBusinessIntent(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("businessIntent is required");
        }
        return BusinessIntent.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
    }

    private QueryIntent parseQueryIntent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("queryIntent must be textual");
        }
        return QueryIntent.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
    }

    private double parseConfidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException("confidence is required");
        }
        double confidence = node.asDouble();
        if (Double.isNaN(confidence) || confidence < 0D || confidence > 1D) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        return confidence;
    }

    private double parseMargin(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException("margin is required");
        }
        double margin = node.asDouble();
        if (Double.isNaN(margin) || margin < 0D || margin > 1D) {
            throw new IllegalArgumentException("margin must be between 0 and 1");
        }
        return margin;
    }

    private Map<String, String> parseEntities(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject() || node.size() > MAX_ENTITY_COUNT) {
            throw new IllegalArgumentException("entities must be a bounded object");
        }

        Map<String, String> entities = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual() || entry.getKey().length() > MAX_ENTITY_VALUE_LENGTH
                    || entry.getValue().asText().length() > MAX_ENTITY_VALUE_LENGTH) {
                throw new IllegalArgumentException("entity values must be bounded text");
            }
            entities.put(entry.getKey(), entry.getValue().asText());
        });
        return entities;
    }

    private IntentDecision mapModelIntent(
            BusinessIntent businessIntent,
            QueryIntent queryIntent,
            double confidence,
            double confidenceMargin,
            Map<String, String> entities
    ) {
        if (SIDE_EFFECT_INTENTS.contains(businessIntent)) {
            return safeClarify(
                    "LLM_SIDE_EFFECT_REQUIRES_EXPLICIT_RULE",
                    IntentRiskLevel.BLOCKED,
                    confidence,
                    confidenceMargin
            );
        }
        return switch (businessIntent) {
            case DIRECT_CHAT -> decision(
                    businessIntent, null, IntentRoute.DIRECT, IntentRiskLevel.NONE,
                    confidence, confidenceMargin, entities, List.of(), false, "LLM_CLASSIFIED_DIRECT"
            );
            case GENERAL_EXPLANATION -> decision(
                    businessIntent, queryIntent, IntentRoute.DIRECT, IntentRiskLevel.READ_ONLY,
                    confidence, confidenceMargin, entities, List.of(), false, "LLM_CLASSIFIED_EXPLANATION"
            );
            case KNOWLEDGE_QA, KNOWLEDGE_FOLLOW_UP, KNOWLEDGE_NAVIGATION -> decision(
                    businessIntent, queryIntent, IntentRoute.KNOWLEDGE, IntentRiskLevel.READ_ONLY,
                    confidence, confidenceMargin, entities, List.of("KnowledgeTool"), false,
                    "LLM_CLASSIFIED_KNOWLEDGE"
            );
            case DATABASE_QUERY -> decision(
                    businessIntent, queryIntent, IntentRoute.TOOL, IntentRiskLevel.READ_ONLY,
                    confidence, confidenceMargin, entities, List.of("databaseQuery"), false,
                    "LLM_CLASSIFIED_DATABASE"
            );
            case WEATHER_QUERY -> decision(
                    businessIntent, queryIntent, IntentRoute.TOOL, IntentRiskLevel.EXTERNAL_READ,
                    confidence, confidenceMargin, entities, List.of("weather"), false,
                    "LLM_CLASSIFIED_WEATHER"
            );
            case DATE_OR_CITY_QUERY -> decision(
                    businessIntent, queryIntent, IntentRoute.TOOL, IntentRiskLevel.READ_ONLY,
                    confidence, confidenceMargin, entities, List.of("getDate", "getCity"), false,
                    "LLM_CLASSIFIED_DATE_OR_CITY"
            );
            case FILE_READ -> decision(
                    businessIntent, queryIntent, IntentRoute.TOOL, IntentRiskLevel.READ_ONLY,
                    confidence, confidenceMargin, entities, List.of("readFile", "listFiles"), false,
                    "LLM_CLASSIFIED_FILE_READ"
            );
            case EXTERNAL_LOOKUP, CLARIFY -> safeClarify(
                    "LLM_INTENT_REQUIRES_POLICY_CONTEXT", IntentRiskLevel.NONE, confidence, confidenceMargin
            );
            case ABSTAIN -> decision(
                    BusinessIntent.ABSTAIN,
                    null,
                    IntentRoute.ABSTAIN,
                    IntentRiskLevel.BLOCKED,
                    confidence,
                    confidenceMargin,
                    entities,
                    List.of(),
                    false,
                    "LLM_CLASSIFIED_ABSTAIN"
            );
            case SEND_EMAIL, FILE_MUTATION, COMPOSITE -> safeClarify(
                    "LLM_SIDE_EFFECT_REQUIRES_EXPLICIT_RULE", IntentRiskLevel.BLOCKED, confidence, confidenceMargin
            );
        };
    }

    private IntentDecision safeClarify(String reasonCode, IntentRiskLevel riskLevel) {
        return safeClarify(reasonCode, riskLevel, 0D, null);
    }

    private IntentDecision safeClarify(String reasonCode, IntentRiskLevel riskLevel, Double confidenceMargin) {
        return safeClarify(reasonCode, riskLevel, 0D, confidenceMargin);
    }

    private IntentDecision safeClarify(
            String reasonCode,
            IntentRiskLevel riskLevel,
            double confidence,
            Double confidenceMargin
    ) {
        return decision(
                BusinessIntent.CLARIFY,
                null,
                IntentRoute.CLARIFY,
                riskLevel,
                confidence,
                confidenceMargin,
                Map.of(),
                List.of(),
                true,
                reasonCode
        );
    }

    private IntentDecision decision(
            BusinessIntent businessIntent,
            QueryIntent queryIntent,
            IntentRoute route,
            IntentRiskLevel riskLevel,
            double confidence,
            Double confidenceMargin,
            Map<String, String> entities,
            List<String> capabilities,
            boolean requiresClarification,
            String reasonCode
    ) {
        return new IntentDecision(
                IntentRouter.DECISION_VERSION,
                businessIntent,
                queryIntent,
                route,
                riskLevel,
                confidence,
                confidenceMargin,
                entities,
                capabilities,
                requiresClarification,
                IntentDecisionSource.LLM_FALLBACK,
                reasonCode,
                IntentRouter.POLICY_VERSION
        );
    }
}
