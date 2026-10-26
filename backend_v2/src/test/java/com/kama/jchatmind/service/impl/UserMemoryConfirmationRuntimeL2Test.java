package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryConsolidationProposalMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.model.entity.UserMemoryConsolidationProposal;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "QUALITY_MEMORY_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/quality_memory_[a-f0-9]+")
class UserMemoryConfirmationRuntimeL2Test {

    private static DriverManagerDataSource dataSource;
    private static SqlSessionTemplate sessions;
    private JdbcTemplate jdbc;
    private UserMemoryMapper memories;
    private UserMemoryCandidateMapper candidates;
    private UserMemoryConsolidationProposalMapper proposals;
    private RagService rag;
    private UserMemoryFacadeService service;
    private String userId;
    private String sessionId;

    @BeforeAll
    static void initializeDatabase() throws Exception {
        dataSource = new DriverManagerDataSource(System.getenv("QUALITY_MEMORY_JDBC_URL"), "postgres", "");
        new ResourceDatabasePopulator(new ClassPathResource("g3-user-memory-acceptance-schema.sql"))
                .execute(dataSource);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeHandlers(new PgVectorTypeHandler());
        factory.setMapperLocations(new ClassPathResource("mapper/UserMemoryMapper.xml"),
                new ClassPathResource("mapper/UserMemoryCandidateMapper.xml"),
                new ClassPathResource("mapper/UserMemoryConsolidationProposalMapper.xml"));
        sessions = new SqlSessionTemplate(factory.getObject());
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        memories = sessions.getMapper(UserMemoryMapper.class);
        candidates = sessions.getMapper(UserMemoryCandidateMapper.class);
        proposals = sessions.getMapper(UserMemoryConsolidationProposalMapper.class);
        rag = mock(RagService.class);
        userId = Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 1));
        sessionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_session (id, user_id, title) VALUES (?::uuid, ?, 'quality test')", sessionId, userId);
        var manager = new DataSourceTransactionManager(dataSource);
        ProxyFactory writer = new ProxyFactory(new UserMemoryWriteService(memories, candidates, proposals));
        writer.setProxyTargetClass(true);
        writer.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var beans = new StaticListableBeanFactory();
        beans.addBean("rag", rag);
        RequestScopeData identity = new RequestScopeData();
        identity.setUserId(Long.valueOf(userId));
        identity.setLogin(true);
        var facade = new UserMemoryFacadeServiceImpl(memories, candidates, mock(ChatMessageFacadeService.class),
                identity, null, beans.getBeanProvider(RagService.class), proposals,
                (UserMemoryWriteService) writer.getProxy());
        ProxyFactory proxy = new ProxyFactory(facade);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (UserMemoryFacadeService) proxy.getProxy();
    }

    @Test
    void shouldRejectCandidateDiscardedWhileEmbeddingWasGenerated() {
        UserMemoryCandidate candidate = candidate();
        when(rag.embed(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(candidates.markDiscardedIfPending(candidate.getId(), userId)).isEqualTo(1);
            return null;
        });
        assertThatThrownBy(() -> service.confirmUserMemoryCandidate(candidate.getId())).isInstanceOf(BizException.class);
        assertThat(candidates.selectByIdAndUserId(candidate.getId(), userId).getStatus()).isEqualTo("DISCARDED");
        assertThat(memoryCount()).isZero();
    }

    @Test
    void shouldRollBackCandidateStatusWhenMemoryInsertFails() {
        UserMemoryCandidate candidate = candidate();
        when(rag.embed(anyString())).thenReturn(null);
        jdbc.execute("CREATE FUNCTION quality_fail_memory_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled failure'; END; $$");
        jdbc.execute("CREATE TRIGGER quality_fail_memory_insert BEFORE INSERT ON user_memory FOR EACH ROW EXECUTE FUNCTION quality_fail_memory_insert()");
        try {
            assertThatThrownBy(() -> service.confirmUserMemoryCandidate(candidate.getId())).isInstanceOf(RuntimeException.class);
            assertThat(candidates.selectByIdAndUserId(candidate.getId(), userId).getStatus()).isEqualTo("PENDING");
            assertThat(memoryCount()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER quality_fail_memory_insert ON user_memory");
            jdbc.execute("DROP FUNCTION quality_fail_memory_insert()");
        }
    }

    @Test
    void shouldConfirmSameCandidateOnlyOnceUnderConcurrentRequests() throws Exception {
        UserMemoryCandidate candidate = candidate();
        CountDownLatch embeddingStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(rag.embed(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            embeddingStarted.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        });
        var workers = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> confirm = () -> {
            try {
                service.confirmUserMemoryCandidate(candidate.getId());
                return true;
            } catch (BizException exception) {
                return false;
            }
        };
        try {
            var first = workers.submit(confirm);
            var second = workers.submit(confirm);
            assertThat(embeddingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(memoryCount()).isEqualTo(1);
            assertThat(candidates.selectByIdAndUserId(candidate.getId(), userId).getStatus()).isEqualTo("PERSISTED");
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void shouldMarkProposalStaleWhenSourceChangesDuringEmbedding() {
        LocalDateTime now = LocalDateTime.now();
        UserMemory first = UserMemory.builder().userId(userId).memoryType("PREFERENCE").content("第一条偏好")
                .importance("medium").createdAt(now).updatedAt(now).build();
        UserMemory second = UserMemory.builder().userId(userId).memoryType("PREFERENCE").content("第二条偏好")
                .importance("medium").createdAt(now).updatedAt(now).build();
        memories.insert(first);
        memories.insert(second);
        List<UserMemory> sources = List.of(memories.selectByIdAndUserId(first.getId(), userId),
                memories.selectByIdAndUserId(second.getId(), userId));
        UserMemoryConsolidationProposal proposal = UserMemoryConsolidationProposal.builder().userId(userId)
                .sourceMemoryIdsJson("[\"" + first.getId() + "\",\"" + second.getId() + "\"]")
                .sourceFingerprint(UserMemoryWriteService.fingerprintMemories(sources))
                .proposedType("PREFERENCE").proposedContent("合并的偏好").build();
        proposals.insert(proposal);
        when(rag.embed(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.update("UPDATE user_memory SET content = '已编辑' WHERE id = ?::uuid", first.getId());
            return null;
        });

        service.confirmMemoryConsolidationProposal(proposal.getId());

        assertThat(jdbc.queryForObject("SELECT status FROM user_memory_consolidation_proposal WHERE id = ?::uuid",
                String.class, proposal.getId())).isEqualTo("STALE");
        assertThat(memoryCount()).isEqualTo(2);
        assertThat(memories.selectActiveByUserId(userId)).hasSize(2);
    }

    private UserMemoryCandidate candidate() {
        UserMemoryCandidate candidate = UserMemoryCandidate.builder().userId(userId).sessionId(sessionId)
                .memoryType("PREFERENCE").content("喜欢简洁回复").importance("medium")
                .status("PENDING").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        candidates.insert(candidate);
        return candidate;
    }

    private int memoryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_memory WHERE user_id = ?", Integer.class, userId);
    }
}
