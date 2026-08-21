package com.kama.jchatmind.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpAccessAuditPersistenceContractTest {

    @Test
    void shouldAppendMcpAccessAuditWithoutUpdateOrDeleteOperations() throws Exception {
        Path mapper = Path.of(
                "src", "main", "java", "com", "kama", "jchatmind", "mapper", "McpPrincipalAccessMapper.java"
        );
        Path mapperXml = Path.of(
                "src", "main", "resources", "mapper", "McpPrincipalAccessMapper.xml"
        );

        assertThat(Files.readString(mapper)).contains("insertAccessAudit");

        String xml = Files.readString(mapperXml).toLowerCase();
        assertThat(xml)
                .contains("<insert id=\"insertaccessaudit\"")
                .contains("insert into mcp_access_audit")
                .doesNotContain("update mcp_access_audit")
                .doesNotContain("delete from mcp_access_audit");
    }
}
