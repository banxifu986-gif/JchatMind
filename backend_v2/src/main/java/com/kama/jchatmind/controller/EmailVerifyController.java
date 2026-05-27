package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.service.EmailService;
import com.kama.jchatmind.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/email")
@AllArgsConstructor
public class EmailVerifyController {

    private final EmailService emailService;
    private final UserService userService;

    @PostMapping("/verify-code")
    public ApiResponse<Void> sendVerifyCode(@RequestParam String email,
                                             @RequestParam String type,
                                             HttpServletRequest request) {
        if (email == null || email.isBlank()) {
            return ApiResponse.error("邮箱不能为空");
        }
        if (type == null || type.isBlank()) {
            return ApiResponse.error("验证码类型不能为空");
        }

        // 邮箱防枚举：LOGIN 时若邮箱未注册，限流照常标记但不发送
        if ("LOGIN".equals(type) && !userService.existsByEmail(email)) {
            return emailService.sendVerificationCode(email, request.getRemoteAddr(), type, false);
        }

        return emailService.sendVerificationCode(email, request.getRemoteAddr(), type, true);
    }
}
