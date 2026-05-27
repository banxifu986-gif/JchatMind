package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class LoginRequest {
    private String account;
    private String email;
    private String password;
    private String verifyCode;
}
