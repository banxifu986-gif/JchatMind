package com.kama.jchatmind.service;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.LoginRequest;
import com.kama.jchatmind.model.request.RegisterRequest;
import com.kama.jchatmind.model.vo.LoginUserVO;
import com.kama.jchatmind.model.vo.RegisterVO;

public interface UserService {
    ApiResponse<RegisterVO> register(RegisterRequest request, String ip);

    ApiResponse<LoginUserVO> login(LoginRequest request, String ip);

    ApiResponse<LoginUserVO> whoami();

    boolean existsByEmail(String email);
}
