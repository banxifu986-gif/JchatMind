package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualExtractionResultParserTest {

    private final VisualExtractionResultParser parser =
            new VisualExtractionResultParser(new ObjectMapper());

    @Test
    void shouldParseValidExtractionResult() {
        String json = """
                {
                  "caption": "订单服务调用库存服务的架构图",
                  "ocrText": "订单服务 -> 库存服务",
                  "summary": "服务调用关系",
                  "visualKind": "DIAGRAM",
                  "confidence": 0.88,
                  "entities": [
                    {"key": "order-service", "name": "订单服务", "type": "SERVICE", "bbox": [10, 20, 120, 70], "confidence": 0.9}
                  ],
                  "relations": [
                    {"subject": "order-service", "predicate": "CALLS", "object": "order-service", "evidence": "箭头", "confidence": 0.8}
                  ]
                }
                """;

        VisualExtractionResult result = parser.parse(json);

        assertThat(result.caption()).isEqualTo("订单服务调用库存服务的架构图");
        assertThat(result.visualKind()).isEqualTo("DIAGRAM");
        assertThat(result.entities()).hasSize(1);
        assertThat(result.relations()).hasSize(1);
    }

    @Test
    void shouldRejectRelationReferencingUndeclaredEntity() {
        String json = """
                {
                  "caption": "架构图",
                  "ocrText": "",
                  "summary": "",
                  "visualKind": "DIAGRAM",
                  "confidence": 0.8,
                  "entities": [
                    {"key": "order-service", "name": "订单服务", "type": "SERVICE"}
                  ],
                  "relations": [
                    {"subject": "order-service", "predicate": "CALLS", "object": "inventory-service"}
                  ]
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未声明实体");
    }

    @Test
    void shouldRejectDuplicateEntityKey() {
        String json = """
                {
                  "caption": "架构图",
                  "visualKind": "DIAGRAM",
                  "confidence": 0.8,
                  "entities": [
                    {"key": "a", "name": "A", "type": "SERVICE"},
                    {"key": "a", "name": "A2", "type": "SERVICE"}
                  ],
                  "relations": []
                }
                """;

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("key 重复");
    }

    @Test
    void shouldRejectIllegalVisualKindAndConfidence() {
        String badKind = """
                {"caption": "x", "visualKind": "PICTURE", "confidence": 0.8, "entities": [], "relations": []}
                """;
        assertThatThrownBy(() -> parser.parse(badKind))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("visualKind");

        String badConfidence = """
                {"caption": "x", "visualKind": "DIAGRAM", "confidence": 1.5, "entities": [], "relations": []}
                """;
        assertThatThrownBy(() -> parser.parse(badConfidence))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void shouldRejectMalformedJson() {
        assertThatThrownBy(() -> parser.parse("not json"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("JSON 非法");
    }
}