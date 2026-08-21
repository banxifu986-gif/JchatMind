package com.kama.jchatmind.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.McpPrincipalAccessMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpPrincipalAccessServiceTest {

    private static final String TEST_CREDENTIAL_FINGERPRINT =
            "e24ed72e58abfb0ed01f1459289f61d7c317c64ba90e1954f8306eb835eedb22";

    @Test
    void shouldResolveActiveCallerByCredentialFingerprintInsteadOfRawCredential() {
        McpPrincipalAccessMapper mapper = mock(McpPrincipalAccessMapper.class);
        McpCallerIdentity expectedCaller = new McpCallerIdentity(11L, 7L);
        when(mapper.selectActiveCallerByCredentialFingerprint(TEST_CREDENTIAL_FINGERPRINT))
                .thenReturn(expectedCaller);
        McpPrincipalAccessService service = service(mapper);

        assertThat(service.resolveCaller("mcp-test-key")).contains(expectedCaller);
        verify(mapper).selectActiveCallerByCredentialFingerprint(TEST_CREDENTIAL_FINGERPRINT);
    }

    @Test
    void shouldAppendAuthenticationAuditWithoutCredentialValue() {
        McpPrincipalAccessMapper mapper = mock(McpPrincipalAccessMapper.class);
        McpPrincipalAccessService service = service(mapper);

        service.recordAuthentication(new McpCallerIdentity(11L, 7L), "trace-1", "ALLOW", "authenticated");

        ArgumentCaptor<McpAccessAuditRecord> auditCaptor = ArgumentCaptor.forClass(McpAccessAuditRecord.class);
        verify(mapper).insertAccessAudit(auditCaptor.capture());
        assertThat(auditCaptor.getValue()).isEqualTo(new McpAccessAuditRecord(
                11L,
                7L,
                "AUTHENTICATE",
                "ALLOW",
                "[]",
                "trace-1",
                "authenticated",
                "{}"
        ));
    }

    @Test
    void shouldAppendKnowledgeQueryAuditWithTargetKnowledgeBaseIdsOnly() {
        McpPrincipalAccessMapper mapper = mock(McpPrincipalAccessMapper.class);
        McpPrincipalAccessService service = service(mapper);

        service.recordKnowledgeQuery(
                new McpCallerIdentity(11L, 7L),
                "trace-2",
                "ALLOW",
                java.util.List.of("kb-1", "kb-2"),
                "retrieved"
        );

        ArgumentCaptor<McpAccessAuditRecord> auditCaptor = ArgumentCaptor.forClass(McpAccessAuditRecord.class);
        verify(mapper).insertAccessAudit(auditCaptor.capture());
        assertThat(auditCaptor.getValue()).isEqualTo(new McpAccessAuditRecord(
                11L,
                7L,
                "KNOWLEDGE_QUERY",
                "ALLOW",
                "[\"kb-1\",\"kb-2\"]",
                "trace-2",
                "retrieved",
                "{}"
        ));
    }

    private McpPrincipalAccessService service(McpPrincipalAccessMapper mapper) {
        return new McpPrincipalAccessService(mapper, new ObjectMapper());
    }
}
