package com.kama.jchatmind.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.McpPrincipalAccessMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class McpPrincipalAccessService {

    private final McpPrincipalAccessMapper mcpPrincipalAccessMapper;
    private final ObjectMapper objectMapper;

    public Optional<McpCallerIdentity> resolveCaller(String credential) {
        if (!StringUtils.hasText(credential)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mcpPrincipalAccessMapper
                .selectActiveCallerByCredentialFingerprint(credentialFingerprint(credential)));
    }

    public void recordAuthentication(
            McpCallerIdentity caller,
            String correlationId,
            String decision,
            String reasonCode
    ) {
        mcpPrincipalAccessMapper.insertAccessAudit(new McpAccessAuditRecord(
                caller == null ? null : caller.principalId(),
                caller == null ? null : caller.userId(),
                "AUTHENTICATE",
                decision,
                "[]",
                correlationId,
                reasonCode,
                "{}"
        ));
    }

    public void recordKnowledgeQuery(
            McpCallerIdentity caller,
            String correlationId,
            String decision,
            List<String> targetKbIds,
            String reasonCode
    ) {
        mcpPrincipalAccessMapper.insertAccessAudit(new McpAccessAuditRecord(
                caller.principalId(),
                caller.userId(),
                "KNOWLEDGE_QUERY",
                decision,
                serializeKbIds(targetKbIds),
                correlationId,
                reasonCode,
                "{}"
        ));
    }

    private String serializeKbIds(List<String> targetKbIds) {
        try {
            return objectMapper.writeValueAsString(targetKbIds == null ? List.of() : targetKbIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化 MCP 知识库审计目标", e);
        }
    }

    private String credentialFingerprint(String credential) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
