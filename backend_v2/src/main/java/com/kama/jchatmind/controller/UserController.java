package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.LoginRequest;
import com.kama.jchatmind.model.request.RegisterRequest;
import com.kama.jchatmind.model.vo.LoginUserVO;
import com.kama.jchatmind.model.vo.RegisterVO;
import com.kama.jchatmind.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@AllArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ApiResponse<RegisterVO> register(@RequestBody RegisterRequest request,
                                             HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        return userService.register(request, ip);
    }

    @PostMapping("/login")
    public ApiResponse<LoginUserVO> login(@RequestBody LoginRequest request,
                                           HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        return userService.login(request, ip);
    }

    @GetMapping("/whoami")
    public ApiResponse<LoginUserVO> whoami() {
        return userService.whoami();
    }
}
