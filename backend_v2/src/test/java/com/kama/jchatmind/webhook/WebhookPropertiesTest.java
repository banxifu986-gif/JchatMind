package com.kama.jchatmind.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookPropertiesTest {

    @Test
    void shouldBindOwnerKbWhitelistAndSeparateInboundAndCallbackCredentials() {
        MapConfigurationPropertySource propertySource = new MapConfigurationPropertySource(Map.of(
                "jchatmind.webhook.sources.build-system.owner-id", "7",
                "jchatmind.webhook.sources.build-system.allowed-kb-ids[0]", "kb-one",
                "jchatmind.webhook.sources.build-system.allowed-kb-ids[1]", "kb-two",
                "jchatmind.webhook.sources.build-system.inbound-signing-key", "inbound-test-key",
                "jchatmind.webhook.sources.build-system.callback-url", "https://example.invalid/hook",
                "jchatmind.webhook.sources.build-system.outbound-signing-key", "outbound-test-key"
        ));
        WebhookProperties properties = new Binder(propertySource)
                .bind("jchatmind.webhook", Bindable.of(WebhookProperties.class))
                .get();

        WebhookProperties.Source source = properties.requireSource("build-system");

        assertThat(source.getOwnerId()).isEqualTo(7L);
        assertThat(source.getAllowedKbIds()).containsExactlyInAnyOrder("kb-one", "kb-two");
        assertThat(source.getCallbackUrl()).isEqualTo("https://example.invalid/hook");
        assertThat(source.toWebhookSource("build-system").signingKey())
                .isEqualTo("inbound-test-key".getBytes(StandardCharsets.UTF_8));
        assertThat(source.outboundSigningKey())
                .isEqualTo("outbound-test-key".getBytes(StandardCharsets.UTF_8));
        assertThat(source.allowsKnowledgeBase("kb-one")).isTrue();
        assertThat(source.allowsKnowledgeBase("kb-other")).isFalse();
    }

    @Test
    void shouldRejectIncompleteSourceConfigurationWithoutEchoingCredentials() {
        WebhookProperties properties = new WebhookProperties();
        WebhookProperties.Source incomplete = new WebhookProperties.Source();
        incomplete.setInboundSigningKey("synthetic-secret-marker");
        incomplete.setAllowedKbIds(Set.of("kb-one"));
        properties.getSources().put("build-system", incomplete);

        assertThatThrownBy(() -> properties.requireSource("build-system"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("synthetic-secret-marker");
        assertThatThrownBy(() -> properties.requireSource("unknown-source"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("synthetic-secret-marker");
    }
}
