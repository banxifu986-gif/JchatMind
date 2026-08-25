package com.kama.jchatmind.deletion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class KnowledgeBaseDeletionTaskConsumerContractTest {

    @Test
    void shouldProvideDedicatedDeletionTaskConsumer() {
        assertThatCode(() -> Class.forName(
                "com.kama.jchatmind.deletion.KnowledgeBaseDeletionTaskConsumer"
        )).doesNotThrowAnyException();
    }
}
