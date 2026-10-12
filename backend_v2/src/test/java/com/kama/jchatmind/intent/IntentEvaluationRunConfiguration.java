package com.kama.jchatmind.intent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

record IntentEvaluationRunConfiguration(
        String mode,
        String modelName,
        String modelVersion,
        String promptVersion,
        String routingConfigSha256
) {

    IntentEvaluationRunConfiguration {
        if (isBlank(mode) || isBlank(modelName) || isBlank(modelVersion)
                || isBlank(promptVersion) || routingConfigSha256 == null
                || !routingConfigSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evaluation run configuration is incomplete");
        }
    }

    static IntentEvaluationRunConfiguration ruleOnly() {
        return from(new IntentRoutingProperties());
    }

    static IntentEvaluationRunConfiguration from(IntentRoutingProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("routing properties must not be null");
        }
        boolean modelEnabled = properties.isLlmEnabled() || properties.isShadowEnabled();
        String mode = properties.isShadowEnabled()
                ? "shadow"
                : properties.isLlmEnabled() ? "llm-fallback" : "rule-only";
        String modelName = modelEnabled ? nonBlankOr(properties.getModel(), "unconfigured") : "none";
        String modelVersion = modelEnabled ? nonBlankOr(properties.getModelVersion(), "unversioned") : "none";
        String promptVersion = modelEnabled ? ChatClientIntentModelClient.PROMPT_VERSION : "none";
        String canonicalConfig = configField("llmEnabled", properties.isLlmEnabled())
                + configField("shadowEnabled", properties.isShadowEnabled())
                + configField("model", properties.getModel())
                + configField("modelVersion", properties.getModelVersion())
                + configField("timeoutMillis", properties.getTimeoutMillis())
                + configField("minConfidence", properties.getMinConfidence())
                + configField("minMargin", properties.getMinMargin())
                + configField("maxInputCharacters", properties.getMaxInputCharacters())
                + configField("maxOutputCharacters", properties.getMaxOutputCharacters())
                + configField("maxRetries", properties.getMaxRetries())
                + configField("maxTelemetryRecords", properties.getMaxTelemetryRecords());
        return new IntentEvaluationRunConfiguration(
                mode,
                modelName,
                modelVersion,
                promptVersion,
                sha256(canonicalConfig)
        );
    }

    static IntentEvaluationRunConfiguration from(IntentFallbackRouter router) {
        if (router == null) {
            throw new IllegalArgumentException("intent fallback router must not be null");
        }
        return from(router.routingPropertiesSnapshot());
    }

    private static String configField(String key, Object value) {
        String text = String.valueOf(value);
        return key.length() + ":" + key + text.length() + ":" + text + "\n";
    }

    private static String nonBlankOr(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
