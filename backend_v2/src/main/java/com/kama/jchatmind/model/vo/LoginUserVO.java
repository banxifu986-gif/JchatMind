package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LoginUserVO {
    private Long userId;
    private String account;
    private String username;
    private String avatarUrl;
    private Integer isAdmin;
    private String email;
    private String school;
    private String signature;
    private LocalDateTime lastLoginAt;
    private String token;
}
