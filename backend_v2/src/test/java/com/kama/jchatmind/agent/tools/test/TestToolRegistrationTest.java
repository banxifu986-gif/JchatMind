package com.kama.jchatmind.agent.tools.test;

import com.kama.jchatmind.agent.tools.ToolType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestToolRegistrationTest {

    @Test
    void shouldKeepTestToolsOutOfTheFixedRuntimeToolSet() {
        assertThat(new DateTool().getType()).isEqualTo(ToolType.OPTIONAL);
        assertThat(new CityTool().getType()).isEqualTo(ToolType.OPTIONAL);
        assertThat(new WeatherTool().getType()).isEqualTo(ToolType.OPTIONAL);
    }
}
