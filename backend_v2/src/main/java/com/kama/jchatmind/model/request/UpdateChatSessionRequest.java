package com.kama.jchatmind.model.request;

import com.kama.jchatmind.model.dto.ChatSessionDTO;
import lombok.Data;

@Data
public class UpdateChatSessionRequest {
    private String title;
    private ChatSessionDTO.MetaData metadata;
}
