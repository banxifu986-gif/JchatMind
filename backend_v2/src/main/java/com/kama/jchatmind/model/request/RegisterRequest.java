package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class RegisterRequest {
    private String account;
    private String username;
    private String password;
    private String email;
    private String verifyCode;
}
