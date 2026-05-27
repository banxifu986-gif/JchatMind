package com.kama.jchatmind.service;

import com.kama.jchatmind.model.common.ApiResponse;

public interface EmailService {

    void sendEmailAsync(String to, String subject, String content);

    ApiResponse<Void> sendVerificationCode(String email, String ip, String type, boolean shouldSend);

    boolean checkVerificationCode(String email, String code, String ip, String type);
}
