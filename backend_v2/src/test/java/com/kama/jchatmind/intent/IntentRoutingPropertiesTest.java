package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRoutingPropertiesTest {

    @Test
    void shouldKeepModelFallbackDisabledByDefault() {
        IntentRoutingProperties properties = new IntentRoutingProperties();

        assertThat(properties.isLlmEnabled()).isFalse();
        assertThat(properties.isShadowEnabled()).isFalse();
        assertThat(properties.getModel()).isEqualTo("deepseek-chat");
        assertThat(properties.getModelVersion()).isEqualTo("unversioned");
        assertThat(properties.getTimeoutMillis()).isEqualTo(1500L);
        assertThat(properties.getMinConfidence()).isEqualTo(0.75D);
        assertThat(properties.getMinMargin()).isEqualTo(0.15D);
        assertThat(properties.getMaxInputCharacters()).isEqualTo(4000);
        assertThat(properties.getMaxOutputCharacters()).isEqualTo(2000);
        assertThat(properties.getMaxRetries()).isZero();
    }

    @Test
    void shouldExposeExplicitlyConfiguredFallbackSettings() {
        IntentRoutingProperties properties = new IntentRoutingProperties();

        properties.setLlmEnabled(true);
        properties.setShadowEnabled(true);
        properties.setModel("glm-4.6");
        properties.setModelVersion("glm-4.6-build-42");
        properties.setTimeoutMillis(800L);
        properties.setMinConfidence(0.82D);
        properties.setMinMargin(0.24D);
        properties.setMaxInputCharacters(1200);
        properties.setMaxOutputCharacters(600);
        properties.setMaxRetries(1);

        assertThat(properties.isLlmEnabled()).isTrue();
        assertThat(properties.isShadowEnabled()).isTrue();
        assertThat(properties.getModel()).isEqualTo("glm-4.6");
        assertThat(properties.getModelVersion()).isEqualTo("glm-4.6-build-42");
        assertThat(properties.getTimeoutMillis()).isEqualTo(800L);
        assertThat(properties.getMinConfidence()).isEqualTo(0.82D);
        assertThat(properties.getMinMargin()).isEqualTo(0.24D);
        assertThat(properties.getMaxInputCharacters()).isEqualTo(1200);
        assertThat(properties.getMaxOutputCharacters()).isEqualTo(600);
        assertThat(properties.getMaxRetries()).isEqualTo(1);
    }
}
