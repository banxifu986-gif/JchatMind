package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.approval.ApprovalStore;
import com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore;
import com.kama.jchatmind.agent.harness.audit.AuditStore;
import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.ChatSessionConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentKnowledgeBaseMapper;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import com.kama.jchatmind.service.impl.ChatMessageFacadeServiceImpl;
import com.kama.jchatmind.service.impl.ChatSessionFacadeServiceImpl;
import com.kama.jchatmind.service.impl.ToolFacadeServiceImpl;
import com.kama.jchatmind.service.impl.UserMemoryFacadeServiceImpl;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.message.SseMessage;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@TestConfiguration
@ImportAutoConfiguration(DeepSeekChatAutoConfiguration.class)
@EnableTransactionManagement(proxyTargetClass = true)
public class G1ModelDrivenSessionScopeRuntimeL2TestConfig {

    private static final int MIN_DYNAMIC_PORT = 49152;
    private static final int MAX_DYNAMIC_PORT = 65535;
    private static final Pattern ISOLATED_DATABASE_URL = Pattern.compile(
            "jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/g1_model_scope_([a-f0-9]{12})"
    );
    private static final Pattern ISOLATION_RUN_NONCE = Pattern.compile("[a-f0-9]{12}");

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public ToolCallingManager toolCallingManager() {
        return ToolCallingManager.builder().build();
    }

    @Bean
    public RetryTemplate retryTemplate() {
        return new RetryTemplate();
    }

    @Bean
    public ResponseErrorHandler responseErrorHandler() {
        return new DefaultResponseErrorHandler();
    }

    @Bean
    public DataSource dataSource() {
        String databaseUrl = requiredProperty("g1.pg.url");
        String runNonce = requiredProperty("g1.pg.nonce");
        Matcher databaseUrlMatcher = ISOLATED_DATABASE_URL.matcher(databaseUrl);
        if (!databaseUrlMatcher.matches()
                || !ISOLATION_RUN_NONCE.matcher(runNonce).matches()
                || !isDynamicDockerPort(databaseUrlMatcher.group(1))
                || !databaseUrlMatcher.group(2).equals(runNonce)) {
            throw new IllegalArgumentException("模型范围 L2 只能连接本地随机隔离 PostgreSQL 数据库");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(databaseUrl);
        dataSource.setUsername("g1scope");
        dataSource.setPassword("");
        return dataSource;
    }

    private boolean isDynamicDockerPort(String port) {
        int portNumber = Integer.parseInt(port);
        return portNumber >= MIN_DYNAMIC_PORT && portNumber <= MAX_DYNAMIC_PORT;
    }

    @Bean
    public SqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeHandlers(new PgVectorTypeHandler());
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            factory.setMapperLocations(
                    resolver.getResource("classpath:mapper/AgentMapper.xml"),
                    resolver.getResource("classpath:mapper/AgentKnowledgeBaseMapper.xml"),
                    resolver.getResource("classpath:mapper/ChatMessageMapper.xml"),
                    resolver.getResource("classpath:mapper/ChatSessionMapper.xml"),
                    resolver.getResource("classpath:mapper/KnowledgeBaseMapper.xml"),
                    resolver.getResource("classpath:mapper/UserMemoryMapper.xml"),
                    resolver.getResource("classpath:mapper/UserMemoryCandidateMapper.xml")
            );
        } catch (Exception e) {
            throw new IllegalStateException("无法加载模型范围 L2 MyBatis 映射", e);
        }
        return factory;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public MapperFactoryBean<AgentMapper> agentMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(AgentMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<AgentKnowledgeBaseMapper> agentKnowledgeBaseMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(AgentKnowledgeBaseMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<ChatMessageMapper> chatMessageMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(ChatMessageMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<ChatSessionMapper> chatSessionMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(ChatSessionMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<KnowledgeBaseMapper> knowledgeBaseMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(KnowledgeBaseMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<UserMemoryMapper> userMemoryMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(UserMemoryMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<UserMemoryCandidateMapper> userMemoryCandidateMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(UserMemoryCandidateMapper.class, sqlSessionFactory);
    }

    @Bean
    public RequestScopeData requestScopeData() {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(71001L);
        return requestScopeData;
    }

    @Bean
    public AgentConverter agentConverter(ObjectMapper objectMapper) {
        return new AgentConverter(objectMapper);
    }

    @Bean
    public ChatMessageConverter chatMessageConverter(ObjectMapper objectMapper) {
        return new ChatMessageConverter(objectMapper);
    }

    @Bean
    public ChatSessionConverter chatSessionConverter(ObjectMapper objectMapper) {
        return new ChatSessionConverter(objectMapper);
    }

    @Bean
    public KnowledgeBaseConverter knowledgeBaseConverter(ObjectMapper objectMapper) {
        return new KnowledgeBaseConverter(objectMapper);
    }

    @Bean
    public AgentKnowledgeBaseBindingService agentKnowledgeBaseBindingService(
            AgentKnowledgeBaseMapper agentKnowledgeBaseMapper
    ) {
        return new AgentKnowledgeBaseBindingService(agentKnowledgeBaseMapper);
    }

    @Bean
    public KnowledgeBaseAccessService knowledgeBaseAccessService(KnowledgeBaseMapper knowledgeBaseMapper) {
        return new KnowledgeBaseAccessService(knowledgeBaseMapper);
    }

    @Bean
    public ChatSessionFacadeService chatSessionFacadeService(
            ChatSessionMapper chatSessionMapper,
            ChatSessionConverter chatSessionConverter,
            RequestScopeData requestScopeData
    ) {
        return new ChatSessionFacadeServiceImpl(chatSessionMapper, chatSessionConverter, requestScopeData);
    }

    @Bean
    public ChatMessageFacadeService chatMessageFacadeService(
            ChatMessageMapper chatMessageMapper,
            ChatMessageConverter chatMessageConverter,
            ChatSessionFacadeService chatSessionFacadeService,
            ApplicationEventPublisher applicationEventPublisher,
            RequestScopeData requestScopeData
    ) {
        return new ChatMessageFacadeServiceImpl(
                chatMessageMapper,
                chatMessageConverter,
                chatSessionFacadeService,
                applicationEventPublisher,
                requestScopeData
        );
    }

    @Bean
    public UserMemoryFacadeService userMemoryFacadeService(
            UserMemoryMapper userMemoryMapper,
            UserMemoryCandidateMapper userMemoryCandidateMapper,
            ChatMessageFacadeService chatMessageFacadeService,
            RequestScopeData requestScopeData
    ) {
        return new UserMemoryFacadeServiceImpl(
                userMemoryMapper,
                userMemoryCandidateMapper,
                chatMessageFacadeService,
                requestScopeData
        );
    }

    @Bean("deepseek-chat")
    public ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.create(deepSeekChatModel);
    }

    @Bean
    public ChatClientRegistry chatClientRegistry(Map<String, ChatClient> chatClients) {
        return new ChatClientRegistry(chatClients);
    }

    @Bean
    @Primary
    public G1ModelDrivenSessionScopeRuntimeL2Test.RecordingRagService recordingRagService() {
        return new G1ModelDrivenSessionScopeRuntimeL2Test.RecordingRagService();
    }

    @Bean
    public KnowledgeTools knowledgeTools(RagService ragService, ChatSessionFacadeService chatSessionFacadeService) {
        return new KnowledgeTools(ragService, chatSessionFacadeService);
    }

    @Bean
    public TerminateTool terminateTool() {
        return new TerminateTool();
    }

    @Bean
    public ToolFacadeService toolFacadeService(KnowledgeTools knowledgeTools, TerminateTool terminateTool) {
        return new ToolFacadeServiceImpl(List.of(knowledgeTools, terminateTool));
    }

    @Bean
    public SseService sseService() {
        return new SseService() {
            @Override
            public SseEmitter connect(String chatSessionId) {
                return new SseEmitter();
            }

            @Override
            public void send(String chatSessionId, SseMessage message) {
            }
        };
    }

    @Bean("externalToolCallbackProvider")
    public ToolCallbackProvider externalToolCallbackProvider() {
        return () -> new ToolCallback[0];
    }

    @Bean
    public HarnessProperties harnessProperties() {
        HarnessProperties properties = new HarnessProperties();
        properties.getHumanApproval().setEnabled(false);
        properties.getCircuitBreaker().setEnabled(false);
        properties.getAudit().setEnabled(false);
        return properties;
    }

    @Bean
    public ApprovalStore approvalStore() {
        return new InMemoryApprovalStore();
    }

    @Bean
    public AuditStore auditStore(HarnessProperties harnessProperties) {
        return new InMemoryAuditStore(harnessProperties);
    }

    @Bean
    public HarnessInterceptorChain harnessInterceptorChain() {
        return new HarnessInterceptorChain(List.of());
    }

    @Bean
    public HarnessRunner harnessRunner(
            HarnessProperties harnessProperties,
            HarnessInterceptorChain harnessInterceptorChain,
            ApprovalStore approvalStore,
            AuditStore auditStore
    ) {
        return new HarnessRunner(harnessProperties, harnessInterceptorChain, approvalStore, auditStore);
    }

    @Bean
    public JChatMindFactory jChatMindFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseConverter knowledgeBaseConverter,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            AgentKnowledgeBaseBindingService agentKnowledgeBaseBindingService,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            UserMemoryFacadeService userMemoryFacadeService,
            @Qualifier("externalToolCallbackProvider") ToolCallbackProvider externalToolCallbackProvider,
            HarnessRunner harnessRunner,
            HarnessInterceptorChain harnessInterceptorChain
    ) {
        return new JChatMindFactory(
                chatClientRegistry,
                sseService,
                agentMapper,
                agentConverter,
                knowledgeBaseConverter,
                knowledgeBaseAccessService,
                agentKnowledgeBaseBindingService,
                toolFacadeService,
                chatMessageFacadeService,
                chatMessageConverter,
                userMemoryFacadeService,
                externalToolCallbackProvider,
                harnessRunner,
                harnessInterceptorChain
        );
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少测试属性: " + name);
        }
        return value;
    }

    private <T> MapperFactoryBean<T> mapperFactory(Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<T> factory = new MapperFactoryBean<>(mapperType);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }
}
