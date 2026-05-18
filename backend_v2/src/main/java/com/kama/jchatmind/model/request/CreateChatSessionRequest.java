package com.kama.jchatmind.model.request;

import com.kama.jchatmind.model.dto.ChatSessionDTO;
import lombok.Data;

@Data
public class CreateChatSessionRequest {
    private String userId;
    private String agentId;
    private String title;
    private ChatSessionDTO.MetaData metadata;
}
