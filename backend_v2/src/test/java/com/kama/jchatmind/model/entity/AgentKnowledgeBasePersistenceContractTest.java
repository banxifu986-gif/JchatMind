package com.kama.jchatmind.model.entity;

import org.junit.jupiter.api.Test;

import java.beans.Introspector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class AgentKnowledgeBasePersistenceContractTest {

    @Test
    void shouldNotExposeKnowledgeBaseBindingsOnThePersistentAgentEntity() throws Exception {
        assertThat(Arrays.stream(Introspector.getBeanInfo(Agent.class).getPropertyDescriptors())
                .map(descriptor -> descriptor.getName()))
                .doesNotContain("allowedKbs");
    }

    @Test
    void shouldNotPersistKnowledgeBaseBindingsAsAgentJsonb() throws Exception {
        String mapper = Files.readString(Path.of("src/main/resources/mapper/AgentMapper.xml"));

        assertThat(mapper).doesNotContain("allowed_kbs");
    }
}
