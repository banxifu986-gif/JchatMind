package com.kama.jchatmind.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentKnowledgeBaseMigrationContractTest {

    private static final Path MIGRATION = Path.of("../sql/knowledge-base/2026-08-18-migrate-agent-knowledge-base.sql");
    private static final Path OWNER_HARDENING_MIGRATION = Path.of(
            "../sql/knowledge-base/2026-08-18-enforce-knowledge-base-owner-not-null.sql"
    );

    @Test
    void shouldCreateAnAuthoritativeRelationshipTableAndRemoveLegacyJsonb() throws Exception {
        String migration = Files.exists(MIGRATION) ? Files.readString(MIGRATION) : "";

        assertThat(migration)
                .contains("CREATE TABLE agent_knowledge_base")
                .contains("FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE")
                .contains("FOREIGN KEY (kb_id) REFERENCES knowledge_base(id) ON DELETE CASCADE")
                .contains("kb.owner_id = a.user_id")
                .contains("ALTER TABLE agent DROP COLUMN allowed_kbs");
    }

    @Test
    void shouldValidateAndEnforceKnowledgeBaseOwnershipAfterCleanup() throws Exception {
        String migration = Files.exists(OWNER_HARDENING_MIGRATION)
                ? Files.readString(OWNER_HARDENING_MIGRATION)
                : "";

        assertThat(migration)
                .contains("VALIDATE CONSTRAINT fk_knowledge_base_owner")
                .contains("VALIDATE CONSTRAINT chk_knowledge_base_owner_required")
                .contains("ALTER COLUMN owner_id SET NOT NULL");
    }
}
