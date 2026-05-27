package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.mapper.EmailFailureMapper;
import com.kama.jchatmind.model.entity.EmailSendFailure;
import com.kama.jchatmind.model.entity.EmailTask;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
@Component
public class EmailDeadLetterConsumer {

    private final EmailFailureMapper emailFailureMapper;

    public EmailDeadLetterConsumer(EmailFailureMapper emailFailureMapper) {
        this.emailFailureMapper = emailFailureMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.EMAIL_DLQ, ackMode = "MANUAL")
    public void onDeadLetter(EmailTask task, Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        EmailSendFailure failure = new EmailSendFailure();
        failure.setTaskId(task.getTaskId());
        failure.setEmail(task.getEmail());
        failure.setType(task.getType());
        failure.setRetryCount(task.getRetryCount());
        failure.setFailureReason(task.getFailureReason());
        failure.setTraceId(task.getTraceId());
        failure.setExpiredFlag(task.getExpireAt() != null && task.getExpireAt().isBefore(LocalDateTime.now()) ? 1 : 0);
        failure.setCreatedAt(task.getCreatedAt());
        failure.setFailedAt(LocalDateTime.now());

        emailFailureMapper.insert(failure);
        channel.basicAck(deliveryTag, false);
        log.warn("死信邮件任务已审计: taskId={}", task.getTaskId());
    }
}
