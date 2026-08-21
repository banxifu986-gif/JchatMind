package com.kama.jchatmind.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G1ModelDrivenSessionScopeRuntimeL2TestConfigTest {

    private final String originalUrl = System.getProperty("g1.pg.url");
    private final String originalNonce = System.getProperty("g1.pg.nonce");
    private final String originalUsername = System.getProperty("g1.pg.username");
    private final String originalPassword = System.getProperty("g1.pg.password");

    @AfterEach
    void restoreProperties() {
        restore("g1.pg.url", originalUrl);
        restore("g1.pg.nonce", originalNonce);
        restore("g1.pg.username", originalUsername);
        restore("g1.pg.password", originalPassword);
    }

    @Test
    void testConfigIsExcludedFromDefaultSpringBootConfigurationDiscovery() {
        assertThat(G1ModelDrivenSessionScopeRuntimeL2TestConfig.class
                .isAnnotationPresent(SpringBootConfiguration.class)).isFalse();
        assertThat(G1ModelDrivenSessionScopeRuntimeL2TestConfig.class
                .isAnnotationPresent(TestConfiguration.class)).isTrue();
    }

    @Test
    void dataSourceRejectsAnythingOtherThanLocalRandomIsolationDatabase() {
        System.setProperty("g1.pg.url", "jdbc:postgresql://127.0.0.1:5432/jchatmind");
        System.setProperty("g1.pg.nonce", "9f35aabbccdd");
        System.setProperty("g1.pg.username", "legacy-test-user");
        System.setProperty("g1.pg.password", "legacy-test-password");

        assertThatThrownBy(() -> new G1ModelDrivenSessionScopeRuntimeL2TestConfig().dataSource())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("隔离 PostgreSQL");
    }

    @Test
    void dataSourceRejectsFixedPortEvenWhenDatabaseNameMatchesNonce() {
        System.setProperty("g1.pg.url", "jdbc:postgresql://127.0.0.1:5432/g1_model_scope_9f35aabbccdd");
        System.setProperty("g1.pg.nonce", "9f35aabbccdd");

        assertThatThrownBy(() -> new G1ModelDrivenSessionScopeRuntimeL2TestConfig().dataSource())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("隔离 PostgreSQL");
    }

    @Test
    void dataSourceRejectsDatabaseNameWhoseSuffixDoesNotMatchRunNonce() {
        System.setProperty("g1.pg.url", "jdbc:postgresql://127.0.0.1:55000/g1_model_scope_0a1b2c3d4e5f");
        System.setProperty("g1.pg.nonce", "9f35aabbccdd");

        assertThatThrownBy(() -> new G1ModelDrivenSessionScopeRuntimeL2TestConfig().dataSource())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("隔离 PostgreSQL");
    }

    @Test
    void dataSourceRejectsNonLoopbackHostEvenWhenPortAndDatabaseNameMatchNonce() {
        System.setProperty("g1.pg.url", "jdbc:postgresql://192.0.2.1:55000/g1_model_scope_9f35aabbccdd");
        System.setProperty("g1.pg.nonce", "9f35aabbccdd");

        assertThatThrownBy(() -> new G1ModelDrivenSessionScopeRuntimeL2TestConfig().dataSource())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("隔离 PostgreSQL");
    }

    @Test
    void dataSourceUsesFixedTrustCredentialsForMatchingLocalRandomIsolationDatabase() {
        System.setProperty("g1.pg.url", "jdbc:postgresql://127.0.0.1:55000/g1_model_scope_9f35aabbccdd");
        System.setProperty("g1.pg.nonce", "9f35aabbccdd");
        System.setProperty("g1.pg.username", "must-not-be-used");
        System.setProperty("g1.pg.password", "must-not-be-used");

        DriverManagerDataSource dataSource = (DriverManagerDataSource) new G1ModelDrivenSessionScopeRuntimeL2TestConfig()
                .dataSource();

        assertThat(dataSource.getUsername()).isEqualTo("g1scope");
        assertThat(dataSource.getPassword()).isEmpty();
    }

    private void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }
}
