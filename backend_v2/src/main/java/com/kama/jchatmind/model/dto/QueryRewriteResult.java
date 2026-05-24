package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryRewriteResult {
    private String query;

    private RagRetrievalContext context;

    private boolean titleQuery;

    @Builder.Default
    private Intent intent = Intent.FACTOID;

    @Builder.Default
    private ContextApplyMode contextApplyMode = ContextApplyMode.NONE;

    @Builder.Default
    private List<String> retrievalQueries = List.of();

    public enum Intent {
        FOLLOW_UP,
        NAVIGATION,
        FACTOID,
        ANALYTICAL
    }

    public enum ContextApplyMode {
        NONE,
        SOFT,
        HARD
    }
}
