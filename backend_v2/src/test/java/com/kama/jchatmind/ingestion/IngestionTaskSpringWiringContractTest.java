package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionTaskSpringWiringContractTest {

    @Test
    void shouldRegisterStateMachineAsSpringComponentForTaskServiceInjection() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "kama", "jchatmind", "ingestion", "IngestionTaskStateMachine.java"
        ));

        assertThat(source)
                .contains("org.springframework.stereotype.Component")
                .contains("@Component");
    }
}
