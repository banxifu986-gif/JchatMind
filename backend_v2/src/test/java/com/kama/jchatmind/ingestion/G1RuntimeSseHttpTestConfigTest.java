package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class G1RuntimeSseHttpTestConfigTest {

    @Test
    void testConfigIsExcludedFromDefaultSpringBootConfigurationDiscovery() {
        assertThat(G1RuntimeSseHttpTestConfig.class
                .isAnnotationPresent(SpringBootConfiguration.class)).isFalse();
        assertThat(G1RuntimeSseHttpTestConfig.class
                .isAnnotationPresent(TestConfiguration.class)).isTrue();
    }
}
