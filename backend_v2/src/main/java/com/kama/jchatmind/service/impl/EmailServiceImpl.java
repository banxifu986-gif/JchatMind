package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.RandomCodeUtil;
import com.kama.jchatmind.auth.RedisKey;
import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.EmailTask;
import com.kama.jchatmind.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    @Value("${spring.mail.username}")
    private String from;

    @Value("${mail.verify-code.expire-minutes:15}")
    private int expireMinutes;

    @Value("${mail.verify-code.limit-expire-seconds:60}")
    private int limitExpireSeconds;

    @Value("${mail.verify-code.max-error-count:5}")
    private int maxErrorCount;

    @Value("${mail.verify-code.error-expire-minutes:5}")
    private int errorExpireMinutes;

    @Value("${mail.verify-code.ip-limit-count:20}")
    private int ipLimitCount;

    @Value("${mail.verify-code.ip-limit-expire-minutes:10}")
    private int ipLimitExpireMinutes;

    @Value("${mail.verify-code.email-ip-limit-count:5}")
    private int emailIpLimitCount;

    @Value("${mail.verify-code.email-ip-limit-expire-minutes:10}")
    private int emailIpLimitExpireMinutes;

    public EmailServiceImpl(JavaMailSender mailSender,
                            StringRedisTemplate redisTemplate,
                            RabbitTemplate rabbitTemplate) {
        this.mailSender = mailSender;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    @Async
    public void sendEmailAsync(String to, String subject, String content) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);
            message.setFrom(from);
            mailSender.send(message);
            log.info("异步发送邮件成功，收件人: {}, 主题: {}", to, subject);
        } catch (Exception e) {
            log.error("异步发送邮件失败，收件人: {}, 主题: {}, 错误: {}", to, subject, e.getMessage(), e);
        }
    }

    @Override
    public ApiResponse<Void> sendVerificationCode(String email, String ip, String type, boolean shouldSend) {
        String emailLimitKey = RedisKey.emailLimit(type, email);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(emailLimitKey))) {
            return ApiResponse.error("验证码已发送，请60秒后重试");
        }

        String ipLimitKey = RedisKey.ipLimit(type, ip);
        String ipCountStr = redisTemplate.opsForValue().get(ipLimitKey);
        int ipCount = ipCountStr != null ? Integer.parseInt(ipCountStr) : 0;
        if (ipCount >= ipLimitCount) {
            return ApiResponse.error("当前IP请求过于频繁，请稍后重试");
        }

        String rateLimitKey = RedisKey.emailIpRateLimit(type, email, ip);
        String rateCountStr = redisTemplate.opsForValue().get(rateLimitKey);
        int rateCount = rateCountStr != null ? Integer.parseInt(rateCountStr) : 0;
        if (rateCount >= emailIpLimitCount) {
            return ApiResponse.error("该邮箱请求过于频繁，请稍后重试");
        }

        if (!shouldSend) {
            redisTemplate.opsForValue().set(emailLimitKey, "1", Duration.ofSeconds(limitExpireSeconds));
            redisTemplate.opsForValue().increment(ipLimitKey);
            redisTemplate.expire(ipLimitKey, Duration.ofMinutes(ipLimitExpireMinutes));
            redisTemplate.opsForValue().increment(rateLimitKey);
            redisTemplate.expire(rateLimitKey, Duration.ofMinutes(emailIpLimitExpireMinutes));
            return ApiResponse.success(null);
        }

        String code = RandomCodeUtil.generate();
        EmailTask task = EmailTask.builder()
                .taskId(UUID.randomUUID().toString())
                .email(email)
                .code(code)
                .type(type)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusMinutes(expireMinutes))
                .requestIp(ip)
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EMAIL_EXCHANGE,
                RabbitMQConfig.EMAIL_ROUTING_KEY,
                task
        );

        redisTemplate.opsForValue().set(emailLimitKey, "1", Duration.ofSeconds(limitExpireSeconds));
        redisTemplate.opsForValue().increment(ipLimitKey);
        redisTemplate.expire(ipLimitKey, Duration.ofMinutes(ipLimitExpireMinutes));
        redisTemplate.opsForValue().increment(rateLimitKey);
        redisTemplate.expire(rateLimitKey, Duration.ofMinutes(emailIpLimitExpireMinutes));

        log.info("验证码已投递到队列: email={}, type={}, taskId={}", email, type, task.getTaskId());
        return ApiResponse.success(null);
    }

    @Override
    public boolean checkVerificationCode(String email, String code, String ip, String type) {
        String errorKey = RedisKey.errorCount(type, email);
        String errorStr = redisTemplate.opsForValue().get(errorKey);
        int errorCount = errorStr != null ? Integer.parseInt(errorStr) : 0;
        if (errorCount >= maxErrorCount) {
            return false;
        }

        String storedCode = redisTemplate.opsForValue().get(RedisKey.verificationCode(type, email));
        if (storedCode == null) {
            return false;
        }

        if (!storedCode.equals(code)) {
            redisTemplate.opsForValue().increment(errorKey);
            redisTemplate.expire(errorKey, Duration.ofMinutes(errorExpireMinutes));

            String ipLimitKey = RedisKey.ipLimit(type, ip);
            redisTemplate.opsForValue().increment(ipLimitKey);
            redisTemplate.expire(ipLimitKey, Duration.ofMinutes(ipLimitExpireMinutes));

            String rateLimitKey = RedisKey.emailIpRateLimit(type, email, ip);
            redisTemplate.opsForValue().increment(rateLimitKey);
            redisTemplate.expire(rateLimitKey, Duration.ofMinutes(emailIpLimitExpireMinutes));
            return false;
        }

        redisTemplate.delete(RedisKey.verificationCode(type, email));
        redisTemplate.delete(errorKey);
        return true;
    }
}
