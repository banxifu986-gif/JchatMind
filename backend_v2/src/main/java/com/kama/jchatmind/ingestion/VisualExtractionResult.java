package com.kama.jchatmind.ingestion;

import java.util.List;

public record VisualExtractionResult(
        String caption,
        String ocrText,
        String summary,
        String visualKind,
        double confidence,
        List<VisualEntity> entities,
        List<VisualRelation> relations
) {

    public record VisualEntity(
            String key,
            String name,
            String type,
            double[] bbox,
            Double confidence
    ) {
    }

    public record VisualRelation(
            String subject,
            String predicate,
            String object,
            String evidence,
            Double confidence
    ) {
    }
}