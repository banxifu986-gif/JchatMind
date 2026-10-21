package com.kama.jchatmind.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "jchatmind.webhook")
public class WebhookProperties {

    private Map<String, Source> sources = new LinkedHashMap<>();

    public Map<String, Source> getSources() {
        return sources;
    }

    public void setSources(Map<String, Source> sources) {
        this.sources = sources == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sources);
    }

    public Source requireSource(String sourceId) {
        if (!StringUtils.hasText(sourceId)) {
            throw new IllegalStateException("Webhook source is not configured");
        }
        Source source = sources.get(sourceId);
        if (source == null) {
            throw new IllegalStateException("Webhook source is not configured");
        }
        if (source.ownerId == null || source.ownerId <= 0
                || source.allowedKbIds == null || source.allowedKbIds.isEmpty()
                || source.allowedKbIds.stream().anyMatch(kbId -> !StringUtils.hasText(kbId))
                || !StringUtils.hasText(source.inboundSigningKey)
                || !isHttpUrl(source.callbackUrl)
                || !StringUtils.hasText(source.outboundSigningKey)) {
            throw new IllegalStateException("Webhook source configuration is invalid");
        }
        return source;
    }

    private boolean isHttpUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute()
                    && uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static class Source {

        private Long ownerId;
        private Set<String> allowedKbIds = new LinkedHashSet<>();
        private String inboundSigningKey;
        private String callbackUrl;
        private String outboundSigningKey;

        public Long getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(Long ownerId) {
            this.ownerId = ownerId;
        }

        public Set<String> getAllowedKbIds() {
            return allowedKbIds;
        }

        public void setAllowedKbIds(Set<String> allowedKbIds) {
            this.allowedKbIds = allowedKbIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedKbIds);
        }

        public String getInboundSigningKey() {
            return inboundSigningKey;
        }

        public void setInboundSigningKey(String inboundSigningKey) {
            this.inboundSigningKey = inboundSigningKey;
        }

        public String getCallbackUrl() {
            return callbackUrl;
        }

        public void setCallbackUrl(String callbackUrl) {
            this.callbackUrl = callbackUrl;
        }

        public String getOutboundSigningKey() {
            return outboundSigningKey;
        }

        public void setOutboundSigningKey(String outboundSigningKey) {
            this.outboundSigningKey = outboundSigningKey;
        }

        boolean allowsKnowledgeBase(String kbId) {
            return allowedKbIds != null && allowedKbIds.contains(kbId);
        }

        WebhookSource toWebhookSource(String sourceId) {
            byte[] key = inboundSigningKey == null ? null : inboundSigningKey.getBytes(StandardCharsets.UTF_8);
            return new WebhookSource(sourceId, key);
        }

        byte[] outboundSigningKey() {
            return outboundSigningKey == null ? null : outboundSigningKey.getBytes(StandardCharsets.UTF_8);
        }
    }
}
