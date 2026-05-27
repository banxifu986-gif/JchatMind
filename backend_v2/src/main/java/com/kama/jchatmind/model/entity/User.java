package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class User {
    private Long userId;
    private String account;
    private String username;
    private String password;
    private Integer gender;
    private LocalDateTime birthday;
    private String avatarUrl;
    private String email;
    private String school;
    private String signature;
    private Integer isBanned;
    private Integer isAdmin;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
