package com.kama.jchatmind.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenInterceptor implements HandlerInterceptor {

    private final RequestScopeData requestScopeData;
    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) {
        String token = request.getHeader("Authorization");

        if (token == null || token.isBlank()) {
            requestScopeData.setLogin(false);
            requestScopeData.setToken(null);
            requestScopeData.setUserId(null);
            return true;
        }

        token = token.replace("Bearer ", "");

        if (jwtUtil.validateToken(token)) {
            Long userId = jwtUtil.getUserIdFromToken(token);
            requestScopeData.setUserId(userId);
            requestScopeData.setToken(token);
            requestScopeData.setLogin(true);
        } else {
            requestScopeData.setLogin(false);
            requestScopeData.setToken(null);
            requestScopeData.setUserId(null);
        }
        return true;
    }
}
