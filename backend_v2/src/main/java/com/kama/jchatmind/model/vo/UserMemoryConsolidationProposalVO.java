package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UserMemoryConsolidationProposalVO {
    private String id;
    private List<String> sourceMemoryIds;
    private String proposedType;
    private String proposedContent;
    private String status;
}
