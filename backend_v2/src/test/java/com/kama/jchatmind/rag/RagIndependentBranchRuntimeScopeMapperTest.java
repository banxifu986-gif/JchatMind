package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagIndependentBranchRuntimeScopeMapperTest {

    @Test
    void mapsTheFrozenLogicalKnowledgeBaseScopeToTheImportedRuntimeUuid() {
        assertThat(RagIndependentBranchRuntimeScopeMapper.toRuntimeKbScope(
                List.of("g2-baseline-kb"), "4ca79ee3-51dd-3c61-a987-130f89957ed5"
        )).containsExactly("4ca79ee3-51dd-3c61-a987-130f89957ed5");
    }

    @Test
    void rejectsAReplayScopeOutsideTheFrozenFixtureBoundary() {
        assertThatThrownBy(() -> RagIndependentBranchRuntimeScopeMapper.toRuntimeKbScope(
                List.of("another-kb"), "4ca79ee3-51dd-3c61-a987-130f89957ed5"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冻结逻辑 KB scope");
    }
}
