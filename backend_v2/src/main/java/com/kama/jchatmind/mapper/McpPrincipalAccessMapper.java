package com.kama.jchatmind.mapper;

import com.kama.jchatmind.mcp.McpCallerIdentity;
import com.kama.jchatmind.mcp.McpAccessAuditRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpPrincipalAccessMapper {

    McpCallerIdentity selectActiveCallerByCredentialFingerprint(
            @Param("credentialFingerprint") String credentialFingerprint
    );

    int insertAccessAudit(McpAccessAuditRecord record);
}
