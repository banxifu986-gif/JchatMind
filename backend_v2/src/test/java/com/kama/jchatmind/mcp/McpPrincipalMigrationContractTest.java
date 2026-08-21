package com.kama.jchatmind.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpPrincipalMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "..",
            "sql",
            "mcp",
            "2026-08-18-create-mcp-principal-access.sql"
    );

    @Test
    void shouldDefineAuditablePrincipalCredentialAndSingleActiveUserGrant() throws Exception {
        assertThat(Files.exists(MIGRATION)).isTrue();

        String migration = Files.readString(MIGRATION).toLowerCase();

        assertThat(migration)
                .contains("create table mcp_principal")
                .contains("create table mcp_principal_credential")
                .contains("credential_fingerprint")
                .contains("create table mcp_principal_user_grant")
                .contains("where revoked_at is null")
                .contains("create table mcp_access_audit")
                .contains("granted_by_user_id")
                .doesNotContain("tenant_id");
    }

    @Test
    void shouldRemoveSharedApiKeyAsMcpAuthorizationSource() throws Exception {
        String config = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "kama",
                "jchatmind",
                "mcp",
                "McpServerConfig.java"
        ));

        assertThat(config)
                .contains("McpPrincipalAccessService")
                .doesNotContain("@Value(\"${mcp.api-key:}\")");
    }
}
