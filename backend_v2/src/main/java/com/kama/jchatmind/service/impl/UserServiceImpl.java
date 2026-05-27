package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.UserMapper;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.User;
import com.kama.jchatmind.model.request.LoginRequest;
import com.kama.jchatmind.model.request.RegisterRequest;
import com.kama.jchatmind.model.vo.LoginUserVO;
import com.kama.jchatmind.model.vo.RegisterVO;
import com.kama.jchatmind.service.EmailService;
import com.kama.jchatmind.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RequestScopeData requestScopeData;
    private final EmailService emailService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<RegisterVO> register(RegisterRequest request, String ip) {
        if (request.getAccount() == null || request.getAccount().isBlank()) {
            throw new BizException("账号不能为空");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BizException("密码不能为空");
        }
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new BizException("用户名不能为空");
        }

        if (userMapper.findByAccount(request.getAccount()) != null) {
            throw new BizException("账号重复");
        }

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            if (userMapper.findByEmail(request.getEmail()) != null) {
                throw new BizException("邮箱已被使用");
            }
        }

        User user = User.builder()
                .account(request.getAccount())
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .isBanned(0)
                .isAdmin(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        userMapper.insert(user);

        String token = jwtUtil.generateToken(user.getUserId());
        RegisterVO vo = RegisterVO.builder()
                .userId(user.getUserId())
                .token(token)
                .build();
        return ApiResponse.success(vo, "注册成功");
    }

    @Override
    public ApiResponse<LoginUserVO> login(LoginRequest request, String ip) {
        User user;

        // 定位用户：验证码模式用邮箱查，密码模式用账号或邮箱查
        if (request.getVerifyCode() != null && !request.getVerifyCode().isBlank()) {
            user = userMapper.findByEmail(request.getEmail());
        } else if (request.getAccount() != null && !request.getAccount().isBlank()) {
            user = userMapper.findByAccount(request.getAccount());
        } else if (request.getEmail() != null && !request.getEmail().isBlank()) {
            user = userMapper.findByEmail(request.getEmail());
        } else {
            throw new BizException("请提供账号或邮箱");
        }

        // 校验凭证
        if (request.getVerifyCode() != null && !request.getVerifyCode().isBlank()) {
            if (user == null) {
                throw new BizException("验证码无效或已过期");
            }
            if (!emailService.checkVerificationCode(request.getEmail(), request.getVerifyCode(), ip, "LOGIN")) {
                throw new BizException("验证码无效或已过期");
            }
        } else {
            if (user == null) {
                throw new BizException("用户不存在");
            }
            if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                throw new BizException("密码错误");
            }
        }

        if (Integer.valueOf(1).equals(user.getIsBanned())) {
            throw new BizException("账号已被封禁");
        }

        String token = jwtUtil.generateToken(user.getUserId());
        userMapper.updateLastLoginAt(user.getUserId());

        LoginUserVO vo = LoginUserVO.builder()
                .userId(user.getUserId())
                .account(user.getAccount())
                .username(user.getUsername())
                .avatarUrl(user.getAvatarUrl())
                .isAdmin(user.getIsAdmin())
                .email(user.getEmail())
                .school(user.getSchool())
                .signature(user.getSignature())
                .lastLoginAt(LocalDateTime.now())
                .token(token)
                .build();
        return ApiResponse.success(vo, "登录成功");
    }

    @Override
    public ApiResponse<LoginUserVO> whoami() {
        Long userId = requestScopeData.getUserId();
        if (userId == null) {
            throw new BizException("用户 ID 异常");
        }

        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (Integer.valueOf(1).equals(user.getIsBanned())) {
            throw new BizException("账号已被封禁");
        }

        String newToken = jwtUtil.generateToken(userId);
        userMapper.updateLastLoginAt(userId);

        LoginUserVO vo = LoginUserVO.builder()
                .userId(user.getUserId())
                .account(user.getAccount())
                .username(user.getUsername())
                .avatarUrl(user.getAvatarUrl())
                .isAdmin(user.getIsAdmin())
                .email(user.getEmail())
                .school(user.getSchool())
                .signature(user.getSignature())
                .lastLoginAt(LocalDateTime.now())
                .token(newToken)
                .build();
        return ApiResponse.success(vo, "自动登录成功");
    }

    @Override
    public boolean existsByEmail(String email) {
        return userMapper.existsByEmail(email);
    }
}
