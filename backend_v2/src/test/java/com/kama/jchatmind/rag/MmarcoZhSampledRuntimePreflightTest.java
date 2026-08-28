package com.kama.jchatmind.rag;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.QueryRewriteService;
import com.kama.jchatmind.service.impl.BgeRerankerService;
import com.kama.jchatmind.service.impl.RagServiceImpl;
import com.kama.jchatmind.service.impl.VchordBm25QueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "rag.eval.mmarco.enabled", matches = "true")
@SpringBootTest(
        classes = MmarcoZhSampledRuntimeEvaluationTest.MmarcoZhSampledRuntimeTestConfig.class,
        properties = {
                MmarcoZhSampledRuntimeEvaluationTest.MMARCO_RERANK_TIMEOUT_PROPERTY,
                MmarcoZhSampledRuntimeEvaluationTest.IVFFLAT_CONNECTION_INIT_SQL
        }
)
@ActiveProfiles("rag-eval")
class MmarcoZhSampledRuntimePreflightTest {

    private static final String ISOLATED_JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final int RERANK_CANDIDATE_COUNT = 50;
    private static final Path MANIFEST_PATH = Path.of(
            "target", "rag-eval", "external", "mmarco-zh-sampled-v3-local-diagnostic",
            "mmarco-zh-sampled-v3-local-diagnostic-manifest.json"
    );

    @Autowired
    private ChunkBgeM3Mapper chunkBgeM3Mapper;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private VchordBm25QueryService vchordBm25QueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Value("${ollama.base-url:http://127.0.0.1:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.embedding-model:bge-m3:latest}")
    private String embeddingModel;

    @Value("${rag.rerank.base-url:http://127.0.0.1:8081}")
    private String rerankerBaseUrl;

    @Value("${rag.rerank.timeout-ms:3000}")
    private int rerankerTimeoutMs;

    @Test
    void returnsTheFullFixedRerankCandidateBudgetForTheFirstFrozenQuery() throws IOException {
        assertIsolatedDatabase();
        String knowledgeBaseId = knowledgeBaseIdFromManifest();
        assertThat(jdbcTemplate.queryForObject("SELECT vector_dims('[0]'::vector)", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SHOW ivfflat.probes", String.class)).isEqualTo("100");
        RagServiceImpl service = new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                queryRewriteService,
                vchordBm25QueryService,
                new BgeRerankerService(WebClient.builder(), false, rerankerBaseUrl, rerankerTimeoutMs),
                ollamaBaseUrl,
                embeddingModel,
                false,
                true,
                true,
                2_048
        );

        List<RagRetrievalResult> results = service.retrieve(
                List.of(knowledgeBaseId), "美国向哪些国家出口小麦", RERANK_CANDIDATE_COUNT
        );

        assertThat(results).hasSize(RERANK_CANDIDATE_COUNT);
        assertThat(results).allSatisfy(result -> assertThat(result.getKbId()).isEqualTo(knowledgeBaseId));
        List<Double> teiScores = new BgeRerankerService(
                WebClient.builder(), true, rerankerBaseUrl, rerankerTimeoutMs
        ).rerank("美国向哪些国家出口小麦", results.stream().map(RagRetrievalResult::getContent).toList());
        assertThat(teiScores).hasSize(RERANK_CANDIDATE_COUNT);
        assertThat(teiScores).allSatisfy(score -> assertThat(score).isNotNull().isFinite());
    }

    private void assertIsolatedDatabase() throws IOException {
        try (var connection = dataSource.getConnection()) {
            if (!ISOLATED_JDBC_URL.equals(connection.getMetaData().getURL())
                    || !"jchatmind_rag_eval".equals(connection.getCatalog())) {
                throw new IllegalStateException("mMARCO 预检只能连接 127.0.0.1:55432/jchatmind_rag_eval");
            }
        } catch (java.sql.SQLException exception) {
            throw new IOException("mMARCO 预检无法验证隔离数据库", exception);
        }
    }

    private String knowledgeBaseIdFromManifest() throws IOException {
        String manifestSha256 = sha256(MANIFEST_PATH);
        return UUID.nameUUIDFromBytes(("mmarco:zh:knowledge-base:" + manifestSha256)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }
}
