package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.request.UpdateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ChatMessageController {

    private final ChatMessageFacadeService chatMessageFacadeService;

    @GetMapping("/chat-messages/session/{sessionId}")
    public ApiResponse<GetChatMessagesResponse> getChatMessagesBySessionId(
            @RequestParam String userId,
            @PathVariable String sessionId
    ) {
        return ApiResponse.success(chatMessageFacadeService.getChatMessagesBySessionId(userId, sessionId));
    }

    @PostMapping("/chat-messages")
    public ApiResponse<CreateChatMessageResponse> createChatMessage(@RequestBody CreateChatMessageRequest request) {
        return ApiResponse.success(chatMessageFacadeService.createChatMessage(request));
    }

    @DeleteMapping("/chat-messages/{chatMessageId}")
    public ApiResponse<Void> deleteChatMessage(@RequestParam String userId, @PathVariable String chatMessageId) {
        chatMessageFacadeService.deleteChatMessage(userId, chatMessageId);
        return ApiResponse.success();
    }

    @PatchMapping("/chat-messages/{chatMessageId}")
    public ApiResponse<Void> updateChatMessage(
            @RequestParam String userId,
            @PathVariable String chatMessageId,
            @RequestBody UpdateChatMessageRequest request
    ) {
        chatMessageFacadeService.updateChatMessage(userId, chatMessageId, request);
        return ApiResponse.success();
    }
}
