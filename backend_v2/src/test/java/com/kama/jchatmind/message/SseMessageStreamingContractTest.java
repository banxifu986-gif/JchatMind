package com.kama.jchatmind.message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SseMessageStreamingContractTest {

    @Test
    void shouldExposeContentDeltaEvent() {
        assertThat(SseMessage.Type.values())
                .extracting(Enum::name)
                .contains("AI_CONTENT_DELTA");
    }
}
