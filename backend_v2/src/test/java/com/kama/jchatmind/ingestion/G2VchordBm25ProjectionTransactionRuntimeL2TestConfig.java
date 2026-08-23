package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentAssetMapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.mock;

@TestConfiguration
@EnableTransactionManagement(proxyTargetClass = true)
public class G2VchordBm25ProjectionTransactionRuntimeL2TestConfig {

    static final Path FIXTURE = Path.of("target", "g2-vchord-bm25-transaction", "fixture.md").toAbsolutePath();

    @Bean
    public DataSource dataSource() {
        String url = requiredSystemProperty("g2.vchord.ingestion.transaction.pg.url");
        if (!"jdbc:postgresql://127.0.0.1:55436/g2vchord".equals(url)) {
            throw new IllegalArgumentException("G2 VectorChord 事务 L2 只能连接指定隔离数据库");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(requiredSystemProperty("g2.vchord.ingestion.transaction.pg.user"));
        dataSource.setPassword(requiredSystemProperty("g2.vchord.ingestion.transaction.pg.password"));
        return dataSource;
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
                    resolver.getResource("classpath:mapper/ChunkBgeM3Mapper.xml"),
                    resolver.getResource("classpath:mapper/Bm25TokenDictionaryMapper.xml")
            );
        } catch (Exception e) {
            throw new IllegalStateException("无法加载 G2 VectorChord 事务 L2 的 MyBatis 映射", e);
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
    public MapperFactoryBean<ChunkBgeM3Mapper> chunkBgeM3Mapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(ChunkBgeM3Mapper.class, sqlSessionFactory);
    }

    @Bean
    public MapperFactoryBean<Bm25TokenDictionaryMapper> bm25TokenDictionaryMapper(SqlSessionFactory sqlSessionFactory) {
        return mapperFactory(Bm25TokenDictionaryMapper.class, sqlSessionFactory);
    }

    @Bean
    public DocumentAssetMapper documentAssetMapper() {
        return mock(DocumentAssetMapper.class);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public DocumentStorageService documentStorageService() {
        return new DocumentStorageService() {
            @Override
            public String saveFile(String kbId, String documentId, org.springframework.web.multipart.MultipartFile file) {
                throw new UnsupportedOperationException("事务 L2 不覆盖上传存储");
            }

            @Override
            public void deleteFile(String filePath) {
                throw new UnsupportedOperationException("事务 L2 不覆盖上传存储");
            }

            @Override
            public Path getFilePath(String filePath) {
                return FIXTURE;
            }

            @Override
            public boolean fileExists(String filePath) {
                return true;
            }
        };
    }

    @Bean
    public MarkdownParserService markdownParserService() {
        return new MarkdownParserService() {
            @Override
            public List<MarkdownSection> parseMarkdown(InputStream inputStream) {
                return List.of(new MarkdownSection(
                        "BM25 事务",
                        "stable token rollback",
                        "BM25 事务",
                        null,
                        1,
                        false,
                        SectionType.LEAF_CONTENT,
                        1,
                        21
                ));
            }

            @Override
            public List<MarkdownSection> parsePdf(InputStream inputStream) {
                throw new UnsupportedOperationException("事务 L2 只覆盖 Markdown");
            }
        };
    }

    @Bean
    public RagService ragService() {
        return new RagService() {
            @Override
            public float[] embed(String text) {
                return new float[]{0.1F, 0.2F};
            }

            @Override
            public List<String> similaritySearch(List<String> kbIds, String title) {
                throw new UnsupportedOperationException("事务 L2 不覆盖检索");
            }

            @Override
            public List<RagRetrievalResult> retrieve(List<String> kbIds, String query, int limit) {
                throw new UnsupportedOperationException("事务 L2 不覆盖检索");
            }

            @Override
            public List<RagRetrievalResult> retrieve(
                    List<String> kbIds,
                    String query,
                    RagRetrievalContext context,
                    int limit
            ) {
                throw new UnsupportedOperationException("事务 L2 不覆盖检索");
            }
        };
    }

    @Bean
    public VchordBm25ProjectionService vchordBm25ProjectionService(Bm25TokenDictionaryMapper mapper) {
        return new VchordBm25ProjectionService(mapper);
    }

    @Bean
    public DefaultIngestionTaskProcessor defaultIngestionTaskProcessor(
            DocumentMapper documentMapper,
            DocumentStorageService documentStorageService,
            ObjectMapper objectMapper,
            MarkdownParserService markdownParserService,
            RagService ragService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            DocumentAssetMapper documentAssetMapper,
            VchordBm25ProjectionService vchordBm25ProjectionService
    ) {
        return new DefaultIngestionTaskProcessor(
                documentMapper,
                documentStorageService,
                objectMapper,
                markdownParserService,
                ragService,
                chunkBgeM3Mapper,
                documentAssetMapper,
                vchordBm25ProjectionService
        );
    }

    private <T> MapperFactoryBean<T> mapperFactory(Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<T> factory = new MapperFactoryBean<>(mapperType);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }

    private String requiredSystemProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少测试属性: " + name);
        }
        return value;
    }
}
