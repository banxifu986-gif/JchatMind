package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.response.DeleteKnowledgeBaseResponse;
import com.kama.jchatmind.model.response.GetKnowledgeBaseDeletionTaskResponse;
import com.kama.jchatmind.service.KnowledgeBaseFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseDeletionHttpContractTest {

    @Test
    void shouldExposeDeletionRequestAndReturnDeletionTaskId() throws Exception {
        KnowledgeBaseFacadeService facadeService = mock(KnowledgeBaseFacadeService.class);
        when(facadeService.deleteKnowledgeBase("kb-1")).thenReturn(DeleteKnowledgeBaseResponse.builder()
                .deletionTaskId("task-1")
                .build());
        KnowledgeBaseController controller = new KnowledgeBaseController(facadeService);

        Method method = KnowledgeBaseController.class.getMethod("deleteKnowledgeBase", String.class);
        ApiResponse<DeleteKnowledgeBaseResponse> response = controller.deleteKnowledgeBase("kb-1");

        assertThat(method.getAnnotation(DeleteMapping.class).value())
                .containsExactly("/knowledge-bases/{knowledgeBaseId}");
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData().getDeletionTaskId()).isEqualTo("task-1");
        verify(facadeService).deleteKnowledgeBase("kb-1");
    }

    @Test
    void shouldExposeDeletionTaskStatusFromOwnerScopedFacade() throws Exception {
        KnowledgeBaseFacadeService facadeService = mock(KnowledgeBaseFacadeService.class);
        when(facadeService.getKnowledgeBaseDeletionTask("task-1"))
                .thenReturn(GetKnowledgeBaseDeletionTaskResponse.builder()
                        .deletionTaskId("task-1")
                        .status("RUNNING")
                        .progress(50)
                        .attemptCount(1)
                        .maxAttempts(3)
                        .build());
        KnowledgeBaseController controller = new KnowledgeBaseController(facadeService);

        Method method = KnowledgeBaseController.class.getMethod(
                "getKnowledgeBaseDeletionTask",
                String.class
        );
        ApiResponse<GetKnowledgeBaseDeletionTaskResponse> response = controller
                .getKnowledgeBaseDeletionTask("task-1");

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/knowledge-base-deletion-tasks/{taskId}");
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData().getDeletionTaskId()).isEqualTo("task-1");
        assertThat(response.getData().getStatus()).isEqualTo("RUNNING");
        assertThat(response.getData().getProgress()).isEqualTo(50);
        assertThat(response.getData().getAttemptCount()).isEqualTo(1);
        assertThat(response.getData().getMaxAttempts()).isEqualTo(3);
        verify(facadeService).getKnowledgeBaseDeletionTask("task-1");
    }
}
