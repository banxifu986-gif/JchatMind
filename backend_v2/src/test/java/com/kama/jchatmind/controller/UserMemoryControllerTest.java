package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.response.GetUserMemoriesResponse;
import com.kama.jchatmind.model.vo.UserMemoryVO;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryControllerTest {

    @Test
    void shouldDelegateGetUserMemoriesToFacade() {
        UserMemoryFacadeService facadeService = mock(UserMemoryFacadeService.class);
        GetUserMemoriesResponse expected = GetUserMemoriesResponse.builder()
                .memories(new UserMemoryVO[0])
                .build();
        when(facadeService.getUserMemories("user-1")).thenReturn(expected);

        UserMemoryController controller = new UserMemoryController(facadeService);

        GetUserMemoriesResponse response = controller.getUserMemories("user-1").getData();
        assertSame(expected, response);
    }
}
