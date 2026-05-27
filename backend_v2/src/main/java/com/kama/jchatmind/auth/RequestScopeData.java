package com.kama.jchatmind.auth;

import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
@Data
public class RequestScopeData {
    private String token;
    private Long userId;
    private boolean isLogin;
}
