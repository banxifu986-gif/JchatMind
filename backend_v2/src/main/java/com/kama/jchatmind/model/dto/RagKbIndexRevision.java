package com.kama.jchatmind.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RagKbIndexRevision {
    private String kbId;

    private Long revision;
}
