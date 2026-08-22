package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.DocumentAssetMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.impl.DocumentFacadeServiceImpl;
import com.kama.jchatmind.service.impl.DocumentStorageServiceImpl;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@TestConfiguration
@EnableTransactionManagement(proxyTargetClass = true)
public class G1RuntimePostgresTestConfig {

    private static final int MIN_DYNAMIC_PORT = 49152;
    private static final int MAX_DYNAMIC_PORT = 65535;
    private static final Pattern ISOLATED_DATABASE_URL = Pattern.compile(
            "jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/g1_ingestion_([a-f0-9]{12})"
    );
    private static final Pattern ISOLATION_RUN_NONCE = Pattern.compile("[a-f0-9]{12}");

    @Bean
    public static PropertySourcesPlaceholderConfigurer documentStoragePathConfigurer() {
        Properties properties = new Properties();
        properties.setProperty("document.storage.base-path", requiredSystemProperty("g1.storage.dir"));
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setProperties(properties);
        return configurer;
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
            throw new IllegalArgumentException("G1 摄入 L2 只能连接本地随机隔离 PostgreSQL 数据库");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(databaseUrl);
        dataSource.setUsername("g1ingestion");
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
                    resolver.getResource("classpath:mapper/DocumentMapper.xml"),
                    resolver.getResource("classpath:mapper/IngestionTaskMapper.xml"),
                    resolver.getResource("classpath:mapper/ChunkBgeM3Mapper.xml"),
                    resolver.getResource("classpath:mapper/DocumentAssetMapper.xml"),
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
    public MapperFactoryBean<ChunkBgeM3Mapper> chunkBgeM3Mapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(ChunkBgeM3Mapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<DocumentAssetMapper> documentAssetMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(DocumentAssetMapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<KnowledgeBaseMapper> knowledgeBaseMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(KnowledgeBaseMapper.class, sqlSessionFactory);
    }

    @Bean
    public RequestScopeData requestScopeData() {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(70001L);
        return requestScopeData;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public DocumentConverter documentConverter(ObjectMapper objectMapper) {
        return new DocumentConverter(objectMapper);
    }

    @Bean
    public DocumentStorageService documentStorageService() {
        return new DocumentStorageServiceImpl();
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
    public DocumentFacadeServiceImpl documentFacadeService(
            DocumentMapper documentMapper,
            DocumentConverter documentConverter,
            ObjectMapper objectMapper,
            DocumentStorageService documentStorageService,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            RequestScopeData requestScopeData,
            IngestionTaskServiceImpl ingestionTaskService
    ) {
        return new DocumentFacadeServiceImpl(
                documentMapper,
                documentConverter,
                objectMapper,
                documentStorageService,
                null,
                knowledgeBaseAccessService,
                requestScopeData,
                ingestionTaskService
        );
    }

    private String requiredProperty(String name) {
        return requiredSystemProperty(name);
    }

    private static String requiredSystemProperty(String name) {
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
