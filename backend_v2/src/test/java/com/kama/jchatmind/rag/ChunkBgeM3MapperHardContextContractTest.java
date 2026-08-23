package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkBgeM3MapperHardContextContractTest {

    private static final Path MAPPER = Path.of(
            "src", "main", "resources", "mapper", "ChunkBgeM3Mapper.xml"
    );
    private static final Path MAPPER_INTERFACE = Path.of(
            "src", "main", "java", "com", "kama", "jchatmind", "mapper", "ChunkBgeM3Mapper.java"
    );

    @Test
    void shouldPushHardContextFiltersBeforeLimitForEveryTitleFallbackChannel() throws Exception {
        String mapper = Files.readString(MAPPER).toLowerCase();
        String mapperInterface = Files.readString(MAPPER_INTERFACE);

        assertThat(mapperInterface)
                .contains("searchByTitleContainsWithContext")
                .contains("searchByTitleKeywordsWithContext")
                .contains("searchByTitleTrigramWithContext");

        assertHardContextBeforeLimit(mapper, "searchbytitlecontainswithcontext");
        assertHardContextBeforeLimit(mapper, "searchbytitlekeywordswithcontext");
        assertHardContextBeforeLimit(mapper, "searchbytitletrigramwithcontext");
    }

    @Test
    void shouldTreatHardContentPathPrefixAsLiteralInEveryScopedChannel() throws Exception {
        String mapper = Files.readString(MAPPER).toLowerCase();

        for (String statementId : List.of(
                "similaritysearchdetailedwithcontext",
                "searchbytitleexactwithcontext",
                "searchbytitlecontainswithcontext",
                "searchbytitlekeywordswithcontext",
                "searchbytitletrigramwithcontext",
                "searchbytitlebm25",
                "searchbycontentbm25"
        )) {
            String statement = selectStatement(mapper, statementId);
            assertThat(statement)
                    .contains("replace(replace(replace(#{contentpathprefix}")
                    .contains("escape '\\'");
        }
    }

    private void assertHardContextBeforeLimit(String mapper, String statementId) {
        String statement = selectStatement(mapper, statementId);
        int limitIndex = statement.indexOf("limit #{limit}");

        assertThat(limitIndex).isGreaterThan(0);
        assertThat(statement)
                .contains("<include refid=\"kbidswhereclause\"/>")
                .contains("metadata->>'sourcename' = #{sourcename}")
                .contains("metadata->>'sourcetype' = #{sourcetype}")
                .contains("metadata->>'contentpath'");
        assertThat(statement.indexOf("metadata->>'sourcename' = #{sourcename}")).isLessThan(limitIndex);
        assertThat(statement.indexOf("metadata->>'sourcetype' = #{sourcetype}")).isLessThan(limitIndex);
        assertThat(statement.indexOf("metadata->>'contentpath'")).isLessThan(limitIndex);
    }

    private String selectStatement(String mapper, String statementId) {
        int start = mapper.indexOf("<select id=\"" + statementId + "\"");
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = mapper.indexOf("</select>", start);
        assertThat(end).isGreaterThan(start);
        return mapper.substring(start, end);
    }
}
