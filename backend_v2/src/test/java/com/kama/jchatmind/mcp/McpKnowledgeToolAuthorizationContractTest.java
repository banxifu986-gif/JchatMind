package com.kama.jchatmind.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpKnowledgeToolAuthorizationContractTest {

    @Test
    void shouldUseResolvedMcpCallerAndKnowledgeBaseAccessServiceBeforeRetrieval() throws Exception {
        String tool = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "kama",
                "jchatmind",
                "mcp",
                "McpKnowledgeTool.java"
        ));

        assertThat(tool)
                .contains("KnowledgeBaseAccessService")
                .contains("RequestContextHolder")
                .contains("CALLER_IDENTITY_ATTRIBUTE")
                .contains("ragService.retrieve");
    }
}
