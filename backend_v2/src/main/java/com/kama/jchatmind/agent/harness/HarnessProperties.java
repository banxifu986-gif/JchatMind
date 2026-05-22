package com.kama.jchatmind.agent.harness;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "jchatmind.harness")
public class HarnessProperties {

    private HumanApproval humanApproval = new HumanApproval();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Audit audit = new Audit();

    @Data
    public static class HumanApproval {
        private boolean enabled = true;
        private List<String> tools = new ArrayList<>();
        private int timeoutSeconds = 300;
    }

    @Data
    public static class CircuitBreaker {
        private boolean enabled = true;
        private List<String> tools = new ArrayList<>();
        private int failureThreshold = 3;
        private int recoveryTimeoutSeconds = 60;
    }

    @Data
    public static class Audit {
        private boolean enabled = true;
        private int maxRecordsPerSession = 1000;
    }
}
