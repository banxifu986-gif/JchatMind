package com.kama.jchatmind.controller;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/sse")
@AllArgsConstructor
public class SseController {

    private final RequestScopeData requestScopeData;
    private final ChatSessionFacadeService chatSessionFacadeService;
    private final SseService sseService;

    @GetMapping(value = "/connect/{chatSessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connect(
            @PathVariable String chatSessionId,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId
    ) {
        Long userId = requestScopeData.getUserId();
        if (!requestScopeData.isLogin() || userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            chatSessionFacadeService.getChatSession(chatSessionId, userId.toString());
        } catch (BizException exception) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseService.connect(chatSessionId, lastEventId));
    }
}
