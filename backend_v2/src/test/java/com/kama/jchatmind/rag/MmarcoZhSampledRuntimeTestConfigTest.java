package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

class MmarcoZhSampledRuntimeTestConfigTest {

    @Test
    void limitsTheRuntimeContextToItsExplicitAutoConfigurations() {
        assertThat(MmarcoZhSampledRuntimeEvaluationTest.MmarcoZhSampledRuntimeTestConfig.class
                .getAnnotation(EnableAutoConfiguration.class)).isNull();
    }

    @Test
    void importsTransactionInfrastructureForNativeBm25Searches() {
        ImportAutoConfiguration importAutoConfiguration = MmarcoZhSampledRuntimeEvaluationTest.MmarcoZhSampledRuntimeTestConfig.class
                .getAnnotation(ImportAutoConfiguration.class);

        assertThat(importAutoConfiguration.value()).contains(
                DataSourceTransactionManagerAutoConfiguration.class,
                TransactionAutoConfiguration.class
        );
    }

    @Test
    void usesTheMeasuredTeiTimeoutOnlyInMmarcoRuntimeContexts() {
        String timeoutProperty = "rag.rerank.timeout-ms=300000";
        SpringBootTest evaluationContext = MmarcoZhSampledRuntimeEvaluationTest.class
                .getAnnotation(SpringBootTest.class);
        SpringBootTest preflightContext = MmarcoZhSampledRuntimePreflightTest.class
                .getAnnotation(SpringBootTest.class);

        assertThat(evaluationContext.properties()).contains(timeoutProperty);
        assertThat(preflightContext.properties()).contains(timeoutProperty);
    }

    @Test
    void fingerprintsTheSerialCpuTeiBatchingConfiguration() {
        assertThat(MmarcoZhSampledRuntimeEvaluationTest.rerankConfiguration(300_000))
                .contains("tei-client-batch-size=32")
                .contains("tei-client-max-concurrent-batches=1");
    }
}
