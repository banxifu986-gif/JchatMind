package com.kama.jchatmind.auth.aspect;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.auth.annotation.NeedLogin;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.UserMapper;
import com.kama.jchatmind.model.entity.User;
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
    private final UserMapper userMapper;

    @Around("@annotation(needLogin)")
    public Object around(ProceedingJoinPoint joinPoint, NeedLogin needLogin) throws Throwable {
        if (!requestScopeData.isLogin()) {
            throw new BizException(401, "用户未登录");
        }

        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            throw new BizException(401, "用户 ID 异常");
        }

        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BizException(401, "用户不存在");
        }
        if (Integer.valueOf(1).equals(user.getIsBanned())) {
            throw new BizException(403, "账号已被封禁");
        }

        return joinPoint.proceed();
    }
}
