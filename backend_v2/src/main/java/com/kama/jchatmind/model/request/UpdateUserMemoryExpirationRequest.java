package com.kama.jchatmind.model.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateUserMemoryExpirationRequest {
    private LocalDateTime expiresAt;
}
