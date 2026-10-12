package com.kama.jchatmind.intent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intent.routing")
public class IntentRoutingProperties {

    private boolean llmEnabled = false;
    private boolean shadowEnabled = false;
    private String model = "deepseek-chat";
    private String modelVersion = "unversioned";
    private long timeoutMillis = 1500L;
    private double minConfidence = 0.75D;
    private double minMargin = 0.15D;
    private int maxInputCharacters = 4000;
    private int maxOutputCharacters = 2000;
    private int maxRetries = 0;
    private int maxTelemetryRecords = 1000;
}
