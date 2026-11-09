package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DmiImageRagEvalMetricsTest {

    @Test
    void shouldComputeCerWithEditDistance() {
        assertThat(DmiImageRagEvalMetrics.cer("Order service calls Inventory service",
                "Order service calls Inventory service")).isZero();
        assertThat(DmiImageRagEvalMetrics.cer("Order service calls Inventory service",
                "Order service calls Inventory service.")).isGreaterThan(0D).isLessThan(0.1D);
        assertThat(DmiImageRagEvalMetrics.cer("", "anything")).isZero();
    }

    @Test
    void shouldComputeEntityF1() {
        double f1 = DmiImageRagEvalMetrics.entityF1(
                Set.of("订单服务", "库存服务"),
                Set.of("订单服务", "库存服务", "统一库存中心"));
        assertThat(f1).isGreaterThan(0.7D).isLessThan(1.0D);
        assertThat(DmiImageRagEvalMetrics.entityF1(Set.of(), Set.of("a"))).isZero();
        assertThat(DmiImageRagEvalMetrics.entityF1(Set.of("a"), Set.of())).isZero();
    }

    @Test
    void shouldComputeHitAndMrr() {
        assertThat(DmiImageRagEvalMetrics.hitAtK(List.of(1, 3, 0), 5)).isEqualTo(2D / 3);
        assertThat(DmiImageRagEvalMetrics.mrr(List.of(1, 3, 0))).isCloseTo((1D + 1D / 3) / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(DmiImageRagEvalMetrics.hitAtK(List.of(), 5)).isZero();
    }
}