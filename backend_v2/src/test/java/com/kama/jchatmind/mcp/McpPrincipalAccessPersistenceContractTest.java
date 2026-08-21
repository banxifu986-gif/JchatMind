package com.kama.jchatmind.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpPrincipalAccessPersistenceContractTest {

    @Test
    void shouldResolveOnlyActiveCredentialAndGrantByFingerprint() throws Exception {
        Path mapper = Path.of(
                "src",
                "main",
                "java",
                "com",
                "kama",
                "jchatmind",
                "mapper",
                "McpPrincipalAccessMapper.java"
        );
        Path mapperXml = Path.of(
                "src",
                "main",
                "resources",
                "mapper",
                "McpPrincipalAccessMapper.xml"
        );

        assertThat(Files.exists(mapper)).isTrue();
        assertThat(Files.exists(mapperXml)).isTrue();
        assertThat(Files.readString(mapper))
                .contains("selectActiveCallerByCredentialFingerprint")
                .contains("credentialFingerprint");

        String xml = Files.readString(mapperXml).toLowerCase();
        assertThat(xml)
                .contains("mcp_principal_credential")
                .contains("credential_fingerprint")
                .contains("p.status = 'active'")
                .contains("c.status = 'active'")
                .contains("c.revoked_at is null")
                .contains("g.revoked_at is null")
                .contains("c.expires_at is null or c.expires_at &gt; current_timestamp");
    }
}
