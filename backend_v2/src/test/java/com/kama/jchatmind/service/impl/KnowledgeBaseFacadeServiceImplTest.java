package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionTask;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.request.CreateKnowledgeBaseRequest;
import com.kama.jchatmind.model.request.UpdateKnowledgeBaseRequest;
import com.kama.jchatmind.model.vo.KnowledgeBaseVO;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.beans.Introspector;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseFacadeServiceImplTest {

    @Test
    void shouldHideLegacyKnowledgeBasesWithoutOwner() throws Exception {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseConverter knowledgeBaseConverter = mock(KnowledgeBaseConverter.class);
        KnowledgeBase legacyKnowledgeBase = KnowledgeBase.builder().id("kb-legacy").build();
        when(knowledgeBaseMapper.selectByOwnerId("7")).thenReturn(List.of(legacyKnowledgeBase));
        when(knowledgeBaseConverter.toVO(legacyKnowledgeBase))
                .thenReturn(KnowledgeBaseVO.builder().id("kb-legacy").build());
        KnowledgeBaseFacadeServiceImpl service = service(knowledgeBaseMapper, knowledgeBaseConverter);

        assertThat(service.getKnowledgeBases().getKnowledgeBases()).isEmpty();
    }

    @Test
    void shouldRejectDeletingLegacyKnowledgeBaseWithoutOwner() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase legacyKnowledgeBase = KnowledgeBase.builder().id("kb-legacy").build();
        when(knowledgeBaseMapper.selectById("kb-legacy")).thenReturn(legacyKnowledgeBase);
        KnowledgeBaseDeletionTaskServiceImpl deletionTaskService = mock(KnowledgeBaseDeletionTaskServiceImpl.class);
        when(deletionTaskService.requestDeletion("kb-legacy"))
                .thenThrow(new BizException("无权访问知识库"));
        KnowledgeBaseFacadeServiceImpl service = service(
                knowledgeBaseMapper,
                mock(KnowledgeBaseConverter.class),
                deletionTaskService
        );

        assertThatThrownBy(() -> service.deleteKnowledgeBase("kb-legacy"))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");
    }

    @Test
    void shouldDeleteKnowledgeBaseWithCurrentOwnerInFinalWrite() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseDeletionTaskServiceImpl deletionTaskService = mock(KnowledgeBaseDeletionTaskServiceImpl.class);
        when(deletionTaskService.requestDeletion("kb-owned")).thenReturn(KnowledgeBaseDeletionTask.builder()
                .id("delete-task-1")
                .build());
        KnowledgeBaseFacadeServiceImpl service = service(
                knowledgeBaseMapper,
                mock(KnowledgeBaseConverter.class),
                deletionTaskService
        );

        assertThat(service.deleteKnowledgeBase("kb-owned").getDeletionTaskId()).isEqualTo("delete-task-1");

        verify(deletionTaskService).requestDeletion("kb-owned");
    }

    @Test
    void shouldRejectUpdatingLegacyKnowledgeBaseWithoutOwner() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase legacyKnowledgeBase = KnowledgeBase.builder().id("kb-legacy").build();
        when(knowledgeBaseMapper.selectById("kb-legacy")).thenReturn(legacyKnowledgeBase);
        when(knowledgeBaseMapper.updateById(any(KnowledgeBase.class))).thenReturn(1);
        KnowledgeBaseFacadeServiceImpl service = service(
                knowledgeBaseMapper,
                new KnowledgeBaseConverter(new ObjectMapper())
        );
        UpdateKnowledgeBaseRequest request = new UpdateKnowledgeBaseRequest();
        request.setName("renamed");

        assertThatThrownBy(() -> service.updateKnowledgeBase("kb-legacy", request))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");
    }

    @Test
    void shouldAssignCurrentUserAsKnowledgeBaseOwnerOnCreate() throws Exception {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        when(knowledgeBaseMapper.insert(any(KnowledgeBase.class))).thenReturn(1);
        KnowledgeBaseFacadeServiceImpl service = service(
                knowledgeBaseMapper,
                new KnowledgeBaseConverter(new ObjectMapper())
        );
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("private-kb");

        service.createKnowledgeBase(request);

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).insert(captor.capture());
        assertThat(readProperty(captor.getValue(), "ownerId")).isEqualTo("7");
    }

    @Test
    void shouldProjectOwnedDeletionTaskStatus() {
        KnowledgeBaseDeletionTaskServiceImpl deletionTaskService = mock(KnowledgeBaseDeletionTaskServiceImpl.class);
        when(deletionTaskService.getTask("delete-task-1")).thenReturn(KnowledgeBaseDeletionTask.builder()
                .id("delete-task-1")
                .status("RETRYING")
                .progress(0)
                .attemptCount(1)
                .maxAttempts(3)
                .errorSummary("IOException")
                .build());
        KnowledgeBaseFacadeServiceImpl service = service(
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeBaseConverter.class),
                deletionTaskService
        );

        var response = service.getKnowledgeBaseDeletionTask("delete-task-1");

        assertThat(response.getDeletionTaskId()).isEqualTo("delete-task-1");
        assertThat(response.getStatus()).isEqualTo("RETRYING");
        assertThat(response.getAttemptCount()).isEqualTo(1);
        assertThat(response.getErrorSummary()).isEqualTo("IOException");
    }

    private KnowledgeBaseFacadeServiceImpl service(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter
    ) {
        return service(knowledgeBaseMapper, knowledgeBaseConverter, mock(KnowledgeBaseDeletionTaskServiceImpl.class));
    }

    private KnowledgeBaseFacadeServiceImpl service(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            KnowledgeBaseDeletionTaskServiceImpl deletionTaskService
    ) {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        return new KnowledgeBaseFacadeServiceImpl(
                knowledgeBaseMapper,
                knowledgeBaseConverter,
                new KnowledgeBaseAccessService(knowledgeBaseMapper),
                requestScopeData,
                deletionTaskService
        );
    }

    private Object readProperty(Object target, String name) throws Exception {
        return Arrays.stream(Introspector.getBeanInfo(target.getClass()).getPropertyDescriptors())
                .filter(descriptor -> descriptor.getName().equals(name))
                .findFirst()
                .filter(descriptor -> descriptor.getReadMethod() != null)
                .map(descriptor -> {
                    try {
                        return descriptor.getReadMethod().invoke(target);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .orElse(null);
    }
}
