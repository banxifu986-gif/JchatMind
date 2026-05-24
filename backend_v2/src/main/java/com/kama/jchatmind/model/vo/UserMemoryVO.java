package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserMemoryVO {
    private String id;
    private String userId;
    private String sessionId;
    private String memoryType;
    private String content;
    private String importance;
    private String evidenceMessageId;
    private String evidenceText;
}
