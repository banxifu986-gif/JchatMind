package com.kama.jchatmind.agent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TerminateToolTest {

    private final TerminateTool tool = new TerminateTool();

    @Test
    void shouldReturnExpectedName() {
        assertEquals("terminate", tool.getName());
    }

    @Test
    void shouldReturnNonEmptyDescription() {
        assertNotNull(tool.getDescription());
    }

    @Test
    void shouldBeFixedToolType() {
        assertEquals(ToolType.FIXED, tool.getType());
    }

    @Test
    void shouldTerminateWithoutError() {
        assertDoesNotThrow(() -> tool.terminate());
    }
}
