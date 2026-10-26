package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.TransactionDefinition;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryConfirmationTransactionTest {

    @Test
    void shouldGenerateEmbeddingOutsideTransactionAndWriteInsideTransaction() {
        UserMemoryMapper memories = mock(UserMemoryMapper.class);
        UserMemoryCandidateMapper candidates = mock(UserMemoryCandidateMapper.class);
        RagService rag = mock(RagService.class);
        AtomicBoolean embeddingInTransaction = new AtomicBoolean();
        AtomicBoolean writeInTransaction = new AtomicBoolean();
        when(rag.embed("喜欢简洁回复")).thenAnswer(invocation -> {
            embeddingInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new float[]{1, 0};
        });
        when(candidates.selectByIdAndUserId("candidate", "7")).thenReturn(UserMemoryCandidate.builder()
                .id("candidate").userId("7").status("PENDING").memoryType("PREFERENCE")
                .content("喜欢简洁回复").build());
        when(candidates.markPersistedIfPending("candidate", "7")).thenReturn(1);
        when(memories.insert(any(UserMemory.class))).thenAnswer(invocation -> {
            writeInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("rag", rag);
        RequestScopeData identity = new RequestScopeData();
        identity.setLogin(true);
        identity.setUserId(7L);
        LocalTransactionManager manager = new LocalTransactionManager();
        ProxyFactory writerFactory = new ProxyFactory(new UserMemoryWriteService(memories, candidates, null));
        writerFactory.setProxyTargetClass(true);
        writerFactory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        UserMemoryFacadeServiceImpl target = new UserMemoryFacadeServiceImpl(
                memories, candidates, mock(ChatMessageFacadeService.class), identity,
                null, beans.getBeanProvider(RagService.class),
                (UserMemoryWriteService) writerFactory.getProxy()
        );
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager,
                new AnnotationTransactionAttributeSource()));
        UserMemoryFacadeService service = (UserMemoryFacadeService) factory.getProxy();

        service.confirmUserMemoryCandidate("candidate");

        assertThat(embeddingInTransaction).isFalse();
        assertThat(writeInTransaction).isTrue();
    }

    private static class LocalTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
