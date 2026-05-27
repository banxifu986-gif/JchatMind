package com.kama.jchatmind.auth.aspect;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.auth.annotation.NeedLogin;
import com.kama.jchatmind.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class NeedLoginAspect {

    private final RequestScopeData requestScopeData;

    @Around("@annotation(needLogin)")
    public Object around(ProceedingJoinPoint joinPoint, NeedLogin needLogin) throws Throwable {
        if (!requestScopeData.isLogin()) {
            return ApiResponse.error("用户未登录");
        }

        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            return ApiResponse.error("用户 ID 异常");
        }

        return joinPoint.proceed();
    }
}
