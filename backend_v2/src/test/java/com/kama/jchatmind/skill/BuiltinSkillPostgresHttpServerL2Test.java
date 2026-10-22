package com.kama.jchatmind.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "g3.skill.http.postgres.server.l2", matches = "true")
class BuiltinSkillPostgresHttpServerL2Test {

    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String DB_USER = "rag_eval";
    private static final String JWT_SECRET =
            "jchatmind-skill-http-server-l2-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final String OWNER_KB_2 = "00000000-0000-0000-0000-000000000102";

    @Test
    void servesSkillRequestsThroughAnEmbeddedServerAndDropsItsIsolatedSchema() throws Exception {
        String schema = "g3_skill_http_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        ServletWebServerApplicationContext context = null;
        try {
            insertFixtures(schema);
            context = startServer(schema);
            int port = context.getWebServer().getPort();
            String token = context.getBean(JwtUtil.class).generateToken(42L);
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> success = post(
                    client,
                    port,
                    "/api/agents/00000000-0000-0000-0000-000000000042/skills/technical-decision-comparison/execute",
                    token,
                    "00000000-0000-0000-0000-000000000201",
                    List.of(OWNER_KB_2)
            );
            assertApiResponse(success, objectMapper, 200);
            JsonNode successBody = objectMapper.readTree(success.body());
            assertThat(successBody.at("/data/output/evidence/0/kbId").asText()).isEqualTo(OWNER_KB_2);

            HttpResponse<String> unboundKb = post(
                    client,
                    port,
                    "/api/agents/00000000-0000-0000-0000-000000000042/skills/technical-decision-comparison/execute",
                    token,
                    "00000000-0000-0000-0000-000000000201",
                    List.of("kb-unbound")
            );
            assertApiResponse(unboundKb, objectMapper, 500);

            HttpResponse<String> foreignOwnerKb = post(
                    client,
                    port,
                    "/api/agents/00000000-0000-0000-0000-000000000084/skills/technical-decision-comparison/execute",
                    token,
                    "00000000-0000-0000-0000-000000000284",
                    List.of()
            );
            assertApiResponse(foreignOwnerKb, objectMapper, 500);
            assertThat(objectMapper.readTree(foreignOwnerKb.body()).path("message").asText())
                    .isEqualTo("无权访问知识库");

            assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT current_schema()", String.class
            )).isEqualTo(schema);
        } finally {
            if (context != null) {
                context.close();
            }
            dropSchema(schema);
        }
        assertSchemaDoesNotExist(schema);
    }

    private ServletWebServerApplicationContext startServer(String schema) {
        String scopedJdbcUrl = JDBC_URL + "?currentSchema=" + schema;
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(ServerConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.config.name=skill-http-server-l2-isolated",
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + scopedJdbcUrl,
                        "--spring.datasource.username=" + DB_USER,
                        "--spring.datasource.password=",
                        "--mybatis.mapper-locations=classpath*:mapper/*.xml",
                        "--mybatis.type-handlers-package=com.kama.jchatmind.typehandler",
                        "--jwt.secret=" + JWT_SECRET,
                        "--jwt.expiration=3600",
                        "--server.address=127.0.0.1",
                        "--server.port=0"
                );
    }

    private HttpResponse<String> post(
            HttpClient client,
            int port,
            String path,
            String token,
            String sessionId,
            List<String> kbIds
    ) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(
                                java.util.Map.of(
                                        "sessionId", sessionId,
                                        "input", java.util.Map.of(
                                                "question", "比较缓存方案",
                                                "kbIds", kbIds
                                        )
                                )
                        )))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private void assertApiResponse(HttpResponse<String> response, ObjectMapper objectMapper, int expectedCode)
            throws Exception {
        if (response.statusCode() != 200) {
            throw new AssertionError("HTTP status=" + response.statusCode() + ", body=" + response.body());
        }
        int businessCode = objectMapper.readTree(response.body()).path("code").asInt();
        if (businessCode != expectedCode) {
            throw new AssertionError("Business code=" + businessCode + ", body=" + response.body());
        }
    }

    private void createSchema(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, "");
             Statement statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(JDBC_URL);
            try (var identity = statement.executeQuery("SELECT current_database(), current_user")) {
                assertThat(identity.next()).isTrue();
                assertThat(identity.getString(1)).isEqualTo("jchatmind_rag_eval");
                assertThat(identity.getString(2)).isEqualTo(DB_USER);
            }
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private void insertFixtures(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                JDBC_URL + "?currentSchema=" + schema, DB_USER, "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + schema + ".agent ("
                    + "id UUID PRIMARY KEY, user_id BIGINT NOT NULL, name TEXT, description TEXT, "
                    + "system_prompt TEXT, model TEXT, allowed_tools JSONB, chat_options JSONB, "
                    + "created_at TIMESTAMP, updated_at TIMESTAMP)");
            statement.execute("CREATE TABLE " + schema + ".agent_knowledge_base ("
                    + "agent_id UUID NOT NULL, kb_id UUID NOT NULL, bound_by_user_id BIGINT NOT NULL, "
                    + "bound_at TIMESTAMP NOT NULL, PRIMARY KEY (agent_id, kb_id))");
            statement.execute("CREATE TABLE " + schema + ".chat_session ("
                    + "id UUID PRIMARY KEY, user_id TEXT NOT NULL, agent_id UUID NOT NULL, title TEXT, "
                    + "metadata JSONB, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
            statement.execute("CREATE TABLE " + schema + ".knowledge_base ("
                    + "id UUID PRIMARY KEY, name VARCHAR(255) NOT NULL, description TEXT, metadata JSONB, "
                    + "owner_id BIGINT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO " + schema + ".agent VALUES "
                    + "('00000000-0000-0000-0000-000000000042', 42, 'owner', '', '', 'test-model', "
                    + "'[\"KnowledgeTool\"]'::jsonb, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                    + "('00000000-0000-0000-0000-000000000084', 42, 'corrupt', '', '', 'test-model', "
                    + "'[\"KnowledgeTool\"]'::jsonb, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO " + schema + ".knowledge_base (id, name, description, metadata, owner_id) "
                    + "VALUES ('00000000-0000-0000-0000-000000000101', 'kb1', '', '{}'::jsonb, 42), "
                    + "('00000000-0000-0000-0000-000000000102', 'kb2', '', '{}'::jsonb, 42), "
                    + "('00000000-0000-0000-0000-000000000184', 'foreign', '', '{}'::jsonb, 84)");
            statement.execute("INSERT INTO " + schema + ".agent_knowledge_base VALUES "
                    + "('00000000-0000-0000-0000-000000000042', '00000000-0000-0000-0000-000000000101', 42, CURRENT_TIMESTAMP), "
                    + "('00000000-0000-0000-0000-000000000042', '00000000-0000-0000-0000-000000000102', 42, CURRENT_TIMESTAMP), "
                    + "('00000000-0000-0000-0000-000000000084', '00000000-0000-0000-0000-000000000184', 42, CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO " + schema + ".chat_session VALUES "
                    + "('00000000-0000-0000-0000-000000000201', '42', "
                    + "'00000000-0000-0000-0000-000000000042', 'owner', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                    + "('00000000-0000-0000-0000-000000000284', '42', "
                    + "'00000000-0000-0000-0000-000000000084', 'corrupt', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    private void dropSchema(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void assertSchemaDoesNotExist(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, "");
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '" + schema + "'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isZero();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BuiltinSkillPostgresHttpL2Test.SkillPostgresHttpConfiguration.class)
    static class ServerConfiguration {

        @Bean
        ServletWebServerFactory servletWebServerFactory() {
            return new TomcatServletWebServerFactory(0);
        }

        @Bean
        ServletRegistrationBean<DispatcherServlet> dispatcherServletRegistration(
                WebApplicationContext applicationContext
        ) {
            ServletRegistrationBean<DispatcherServlet> registration =
                    new ServletRegistrationBean<>(new DispatcherServlet(applicationContext), "/");
            registration.setLoadOnStartup(1);
            return registration;
        }
    }
}
