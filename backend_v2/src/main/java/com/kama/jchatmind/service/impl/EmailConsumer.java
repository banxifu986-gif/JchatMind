package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.RedisKey;
import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.mapper.EmailFailureMapper;
import com.kama.jchatmind.model.entity.EmailSendFailure;
import com.kama.jchatmind.model.entity.EmailTask;
import com.rabbitmq.client.Channel;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
public class EmailConsumer {

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final EmailFailureMapper emailFailureMapper;
    private final TemplateEngine templateEngine;

    public EmailConsumer(JavaMailSender mailSender,
                         StringRedisTemplate redisTemplate,
                         RabbitTemplate rabbitTemplate,
                         EmailFailureMapper emailFailureMapper,
                         TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.emailFailureMapper = emailFailureMapper;
        this.templateEngine = templateEngine;
    }

    @RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE, ackMode = "MANUAL")
    public void onMessage(EmailTask task, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        String idempotentKey = RedisKey.idempotent(task.getTaskId());
        Boolean alreadyDone = redisTemplate.hasKey(idempotentKey);
        if (Boolean.TRUE.equals(alreadyDone)) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (task.getExpireAt() != null && task.getExpireAt().isBefore(LocalDateTime.now())) {
            routeToDlq(task, "expired");
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(task.getEmail());
            helper.setSubject(getSubject(task.getType()));
            helper.setFrom("");

            Context context = new Context();
            context.setVariable("code", task.getCode());
            context.setVariable("expireMinutes", 15);
            String html = templateEngine.process("mail/verify-code", context);
            helper.setText(html, true);

            mailSender.send(message);

            redisTemplate.opsForValue().set(
                    RedisKey.verificationCode(task.getType(), task.getEmail()),
                    task.getCode(),
                    Duration.ofMinutes(15)
            );
            redisTemplate.opsForValue().set(idempotentKey, "1", Duration.ofHours(1));

            channel.basicAck(deliveryTag, false);
            log.info("验证码邮件发送成功: taskId={}, email={}", task.getTaskId(), task.getEmail());
        } catch (Exception e) {
            log.error("验证码邮件发送失败: taskId={}, retryCount={}", task.getTaskId(), task.getRetryCount(), e);

            int nextRetry = task.getRetryCount() + 1;
            if (nextRetry < RabbitMQConfig.EMAIL_MAX_RETRY_COUNT) {
                task.setRetryCount(nextRetry);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EMAIL_RETRY_EXCHANGE,
                        RabbitMQConfig.EMAIL_RETRY_ROUTING_KEY,
                        task
                );
                channel.basicAck(deliveryTag, false);
            } else {
                routeToDlq(task, e.getMessage());
                channel.basicAck(deliveryTag, false);
            }
        }
    }

    private void routeToDlq(EmailTask task, String reason) {
        EmailSendFailure failure = new EmailSendFailure();
        failure.setTaskId(task.getTaskId());
        failure.setEmail(task.getEmail());
        failure.setType(task.getType());
        failure.setRetryCount(task.getRetryCount());
        failure.setFailureReason(reason);
        failure.setTraceId(task.getTraceId());
        failure.setExpiredFlag(task.getExpireAt() != null && task.getExpireAt().isBefore(LocalDateTime.now()) ? 1 : 0);
        failure.setCreatedAt(task.getCreatedAt());
        failure.setFailedAt(LocalDateTime.now());
        emailFailureMapper.insert(failure);
        log.warn("邮件任务进入死信: taskId={}, reason={}", task.getTaskId(), reason);
    }

    private String getSubject(String type) {
        return switch (type) {
            case "LOGIN" -> "JChatMind 登录验证码";
            case "REGISTER" -> "JChatMind 注册验证码";
            case "RESET_PASSWORD" -> "JChatMind 重置密码验证码";
            default -> "JChatMind 验证码";
        };
    }
}
