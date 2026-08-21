package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.auth.TokenInterceptor;
import com.kama.jchatmind.config.WebConfig;
import com.kama.jchatmind.controller.IngestionTaskSseController;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;

@TestConfiguration
@EnableAutoConfiguration
@EnableTransactionManagement(proxyTargetClass = true)
public class G1RuntimeSseHttpTestConfig {

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(requiredProperty("g1.pg.url"));
        dataSource.setUsername(requiredProperty("g1.pg.username"));
        dataSource.setPassword(requiredProperty("g1.pg.password"));
        return dataSource;
    }

    @Bean
    public SqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            factory.setMapperLocations(
                    resolver.getResource("classpath:mapper/DocumentMapper.xml"),
                    resolver.getResource("classpath:mapper/IngestionTaskMapper.xml"),
                    resolver.getResource("classpath:mapper/KnowledgeBaseMapper.xml")
            );
        } catch (Exception e) {
            throw new IllegalStateException("无法加载 MyBatis 映射文件", e);
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
    public MapperFactoryBean<DocumentMapper> documentMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(DocumentMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<IngestionTaskMapper> ingestionTaskMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(IngestionTaskMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<KnowledgeBaseMapper> knowledgeBaseMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(KnowledgeBaseMapper.class, sqlSessionFactory);
    }

    @Bean
    @Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
    public RequestScopeData requestScopeData() {
        return new RequestScopeData();
    }

    @Bean
    public KnowledgeBaseAccessService knowledgeBaseAccessService(KnowledgeBaseMapper mapper) {
        return new KnowledgeBaseAccessService(mapper);
    }

    @Bean
    public IngestionTaskStateMachine ingestionTaskStateMachine() {
        return new IngestionTaskStateMachine();
    }

    @Bean
    public IngestionTaskPublisher ingestionTaskPublisher() {
        return taskId -> {
        };
    }

    @Bean
    public IngestionTaskServiceImpl ingestionTaskService(
            IngestionTaskMapper ingestionTaskMapper,
            DocumentMapper documentMapper,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            RequestScopeData requestScopeData,
            IngestionTaskStateMachine stateMachine,
            IngestionTaskPublisher ingestionTaskPublisher
    ) {
        return new IngestionTaskServiceImpl(
                ingestionTaskMapper,
                documentMapper,
                knowledgeBaseAccessService,
                requestScopeData,
                stateMachine,
                ingestionTaskPublisher
        );
    }

    @Bean
    public IngestionTaskProgressServiceImpl ingestionTaskProgressService(ObjectMapper objectMapper) {
        return new IngestionTaskProgressServiceImpl(objectMapper);
    }

    @Bean
    public IngestionTaskSseController ingestionTaskSseController(
            IngestionTaskServiceImpl ingestionTaskService,
            IngestionTaskProgressService progressService
    ) {
        return new IngestionTaskSseController(ingestionTaskService, progressService);
    }

    @Bean
    public JwtUtil jwtUtil() {
        return new JwtUtil();
    }

    @Bean
    public TokenInterceptor tokenInterceptor(RequestScopeData requestScopeData, JwtUtil jwtUtil) {
        return new TokenInterceptor(requestScopeData, jwtUtil);
    }

    @Bean
    public WebConfig webConfig(TokenInterceptor tokenInterceptor) {
        return new WebConfig(tokenInterceptor);
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少测试属性 " + name);
        }
        return value;
    }

    private <T> MapperFactoryBean<T> mapperFactory(Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<T> factory = new MapperFactoryBean<>(mapperType);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }
}
