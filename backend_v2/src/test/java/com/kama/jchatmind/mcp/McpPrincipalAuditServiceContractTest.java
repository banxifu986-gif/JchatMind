package com.kama.jchatmind.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpPrincipalAuditServiceContractTest {

    @Test
    void shouldExposeAuthenticationAuditEntryPoint() throws Exception {
        String service = Files.readString(Path.of(
                "src", "main", "java", "com", "kama", "jchatmind", "mcp", "McpPrincipalAccessService.java"
        ));

        assertThat(service).contains("recordAuthentication");
        assertThat(service).contains("recordKnowledgeQuery");
    }
}
