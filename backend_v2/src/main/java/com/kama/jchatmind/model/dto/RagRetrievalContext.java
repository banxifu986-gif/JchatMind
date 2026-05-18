package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrievalContext {
    private String sourceType;

    private String sourceName;

    private String contentPath;

    public boolean hasContext() {
        return StringUtils.hasText(sourceType)
                || StringUtils.hasText(sourceName)
                || StringUtils.hasText(contentPath);
    }
}
