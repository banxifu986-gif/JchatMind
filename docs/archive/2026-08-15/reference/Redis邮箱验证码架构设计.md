# Redis 邮箱验证码架构设计

## 1. 架构全景

```
┌─────────┐  POST /api/email/verify-code   ┌──────────────────┐
│ 前端     │ ──────────────────────────────→ │ EmailController  │
│         │ ←────── { success } ──────────── │                  │
└─────────┘                                 └────────┬─────────┘
                                                     │
                                                     ▼
                                          ┌──────────────────────┐
                                          │  EmailServiceImpl     │
                                          │                      │
                                          │  1. 三层 Redis 限流    │
                                          │  2. 生成 6 位验证码    │
                                          │  3. 封装 EmailTask     │
                                          │  4. 投递 RabbitMQ      │
                                          └──────────┬───────────┘
                                                     │
                                              ┌──────▼──────┐
                                              │  RabbitMQ    │
                                              │  email.queue │
                                              └──────┬──────┘
                                                     │
                                                     ▼
                                          ┌──────────────────────┐
                                          │  EmailConsumer        │
                                          │                      │
                                          │  1. 幂等检查          │
                                          │  2. 过期检查          │
                                          │  3. JavaMail 发送     │
                                          │  4. 验证码写入 Redis   │
                                          │  5. 失败→重试/死信    │
                                          └──────────────────────┘
                                          失败 3 次后
                                                     │
                                                     ▼
                                          ┌──────────────────────┐
                                          │  EmailDeadLetter      │
                                          │  Consumer             │
                                          │  → MySQL 审计记录     │
                                          └──────────────────────┘

┌─────────┐  POST /api/users (带 verifyCode) ┌──────────────────┐
│ 注册/登录│ ────────────────────────────────→ │ UserServiceImpl   │
│         │ ←────── JWT ──────────────────── │  checkVerification│
└─────────┘                                  │  Code() → Redis   │
                                             └──────────────────┘
```

## 2. Redis Key 设计 — `RedisKey.java`

```java
public class RedisKey {
    // 验证码存储
    // → email:register:verification_code:user@example.com
    public static String verificationCode(String type, String email) {
        return "email:" + type.toLowerCase() + ":verification_code:" + email;
    }

    // 邮箱级发送频率限制 (60 秒内不可重复发送)
    // → email:register:verification_code:limit:user@example.com
    public static String verificationLimitCode(String type, String email) {
        return "email:" + type.toLowerCase() + ":verification_code:limit:" + email;
    }

    // 验证错误计数
    // → email:register:verify_code:error_count:user@example.com
    public static String verificationErrorCount(String type, String email) {
        return "email:" + type.toLowerCase() + ":verify_code:error_count:" + email;
    }

    // IP 级频率限制 (IP 用 MD5 哈希，避免日志泄露)
    // → email:register:verification_code:limit:ip:<MD5>
    public static String verificationIpRateLimit(String type, String ip) {
        return "email:" + type.toLowerCase() + ":verification_code:limit:ip:"
                + DigestUtils.md5DigestAsHex(ip.getBytes());
    }

    // 邮箱+IP 联合限流
    // → email:register:verification_code:rate_limit:<MD5(type:email:ip)>
    public static String verificationEmailIpRateLimit(String type, String email, String ip) {
        String rawKey = type + ":" + email + ":" + ip;
        return "email:" + type.toLowerCase() + ":verification_code:rate_limit:"
                + DigestUtils.md5DigestAsHex(rawKey.getBytes());
    }

    // 幂等标记
    // → email:task:done:<taskId>
    public static String emailTaskDone(String taskId) {
        return "email:task:done:" + taskId;
    }
}
```

## 3. 发送验证码流程 — `EmailServiceImpl.sendVerificationCode()`

```java
@Override
public String sendVerificationCode(String email, String ip, VerifyCodeType type, boolean shouldSend) {

    // ===== 第一层：邮箱级频率限制 (60 秒) =====
    if (isVerificationCodeRateLimited(email, type)) {
        throw new BusinessException("验证码发送过于频繁，请 60 秒后重试");
    }

    // ===== 第二层：IP 级频率限制 =====
    String ipRateLimitKey = RedisKey.verificationIpRateLimit(type.name(), ip);
    String ipCount = redisTemplate.opsForValue().get(ipRateLimitKey);
    if (ipCount != null && Integer.parseInt(ipCount) >= ipLimitCount) {
        throw new BusinessException("当前 IP 请求过于频繁，请稍后重试");
    }

    // ===== 第三层：邮箱+IP 联合限流 =====
    String emailIpRateLimitKey = RedisKey.verificationEmailIpRateLimit(type.name(), email, ip);
    String emailIpCount = redisTemplate.opsForValue().get(emailIpRateLimitKey);
    if (emailIpCount != null && Integer.parseInt(emailIpCount) >= emailIpLimitCount) {
        throw new BusinessException("验证码发送过于频繁，请稍后重试");
    }

    // ===== 生成验证码 =====
    String verificationCode = shouldSend ? RandomCodeUtil.generateNumberCode(6) : null;

    // ===== 投递 RabbitMQ =====
    if (shouldSend) {
        EmailTask task = buildEmailTask(email, ip, type, verificationCode);
        publishEmailTask(task, EMAIL_EXCHANGE, EMAIL_ROUTING_KEY); // Publisher Confirm 等待 5 秒
        markRateLimit(type, email, ip, ipRateLimitKey, emailIpRateLimitKey);
    } else {
        // 邮箱未注册（LOGIN 场景），仍然标记发送限制防枚举
        markShortRateLimit(type, email);
    }
    return verificationCode;
}
```

**三层限流总结：**

| 层级 | Redis Key | 限制内容 | TTL |
|---|---|---|---|
| L1 | `...limit:{email}` | 同一邮箱 60 秒内不可重复发送 | 60s |
| L2 | `...limit:ip:{MD5}` | 同一 IP 每小时最多 20 次 | 10min |
| L3 | `...rate_limit:{MD5}` | 同一邮箱+IP 组合最多 5 次 | 10min |

## 4. 邮箱防枚举设计

```java
// EmailController 中 LOGIN 类型验证码的处理
// 邮箱未注册 → 不生成验证码，但标记限流 + 返回相同成功响应
// 防止攻击者通过"是否收到验证码"来判断邮箱是否已注册

if (type == VerifyCodeType.LOGIN && !userService.existsByEmail(email)) {
    // shouldSend = false → 不生成验证码，但标记限制
    emailService.sendVerificationCode(email, ip, type, false);
    return ApiResponseUtil.success("验证码发送成功");  // 返回相同响应
}
```

## 5. RabbitMQ 拓扑设计 — `RabbitMQConfig.java`

```
                    ┌──────────────┐
    发送方 ───────→ │email.exchange│ (Direct)
                    └──────┬───────┘
                           │ routingKey: "email.send"
                           ▼
                    ┌──────────────┐    失败+过期     ┌──────────────┐
                    │ email.queue  │ ──────────────→ │   email.dlx  │
                    │ (DLX绑定)    │                  │  (Direct)    │
                    └──────┬───────┘                  └──────┬───────┘
                           │                                │
                    EmailConsumer                    EmailDeadLetterConsumer
                    成功 → 存入 Redis                       │
                    失败 → 重试(最多3次)             ┌──────▼────────┐
                           │                        │  email.dlq    │
                           ▼                        │  → MySQL 审计  │
                    ┌──────────────┐                └───────────────┘
                    │email.retry.  │
                    │exchange      │
                    │(TTL 30s)     │
                    └──────────────┘
                    30 秒后 → dead letter 回到 email.exchange
```

```java
// 常量定义
public static final String EMAIL_QUEUE           = "email.queue";
public static final String EMAIL_EXCHANGE        = "email.exchange";
public static final String EMAIL_ROUTING_KEY     = "email.send";
public static final String EMAIL_RETRY_QUEUE     = "email.retry.queue";
public static final String EMAIL_RETRY_EXCHANGE  = "email.retry.exchange";
public static final String EMAIL_RETRY_ROUTING_KEY = "email.retry";
public static final String EMAIL_DLQ             = "email.dlq";
public static final String EMAIL_DLX             = "email.dlx";
public static final String EMAIL_DEAD_ROUTING_KEY = "email.dead";
public static final int EMAIL_MAX_RETRY_COUNT    = 3;
public static final int EMAIL_RETRY_TTL_MILLIS   = 30_000;  // 30秒
```

**重试死信机制：**
- 主队列 `email.queue` 绑定 DLX `email.dlx`
- 消费失败 + 未超过 3 次 → 投递 `email.retry.queue`（TTL 30 秒）
- 30 秒后消息过期，通过 dead letter 机制自动回到 `email.exchange` → 重新消费
- 超过 3 次或已过期 → 投递 `email.dlq` → `EmailDeadLetterConsumer` → MySQL 审计

## 6. 邮件消费者 — `EmailConsumer.java`

```java
@RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE)
public void handleEmailTask(EmailTask task, Message message, Channel channel) {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();

    // ① 幂等检查：防止重复消费
    if (isTaskDone(task.getTaskId())) {
        channel.basicAck(deliveryTag, false);
        return;
    }

    // ② 过期检查
    if (isExpired(task)) {
        deadLetter(task, "TASK_EXPIRED");
        channel.basicAck(deliveryTag, false);
        return;
    }

    // ③ 发送邮件 (Thymeleaf 模板)
    sendEmail(task);
    // → JavaMailSender + MimeMessageHelper
    // → TemplateEngine.process("mail/verify-code", context)
    // → 验证码存入 Redis：email:{type}:verification_code:{email}
    markTaskDone(task.getTaskId());  // 幂等标记
    channel.basicAck(deliveryTag, false);

    // ④ 失败处理
    // 未过期 + 未超最大重试次数 → 投递 email.retry.exchange
    // 否则 → 投递 email.dlx (死信)
}
```

**sendEmail 详细实现：**
```java
private void sendEmail(EmailTask task) throws Exception {
    MimeMessage mimeMessage = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

    helper.setFrom(fromEmail);
    helper.setTo(task.getEmail());
    helper.setSubject("【Lotso笔记】邮箱验证码");

    // Thymeleaf 模板渲染
    Context context = new Context();
    context.setVariable("verifyCode", task.getCode());
    context.setVariable("expireMinutes", expireMinutes);
    String htmlContent = templateEngine.process("mail/verify-code", context);
    helper.setText(htmlContent, true);

    mailSender.send(mimeMessage);

    // 验证码存入 Redis，TTL 为剩余过期时间
    String redisKey = RedisKey.verificationCode(task.getType(), task.getEmail());
    long ttlMillis = Math.max(task.getExpireAt() - System.currentTimeMillis(), 1L);
    redisTemplate.opsForValue().set(redisKey, task.getCode(), ttlMillis, TimeUnit.MILLISECONDS);
}
```

## 7. 校验验证码 — `EmailServiceImpl.checkVerificationCode()`

```java
@Override
public boolean checkVerificationCode(String email, String code, String ip, VerifyCodeType type) {
    // ① 前置拦截：错误次数/IP限制/联合限制超标直接拒绝
    if (errorCount >= maxErrorCount) return false;
    if (ipCount >= ipLimitCount) return false;
    if (emailIpCount >= emailIpLimitCount) return false;

    // ② 从 Redis 读取验证码比对
    String verificationCode = redisTemplate.opsForValue().get(redisKey);

    if (verificationCode != null && verificationCode.equals(code)) {
        // 成功：删除验证码和错误计数
        redisTemplate.delete(redisKey);
        redisTemplate.delete(errorCountKey);
        return true;
    }

    // ③ 失败：递增错误计数 + IP 计数 + Email+IP 计数（全部带 TTL）
    redisTemplate.opsForValue().increment(errorCountKey);
    redisTemplate.opsForValue().increment(ipRateLimitKey);
    redisTemplate.opsForValue().increment(emailIpRateLimitKey);
    return false;
}
```

## 8. EmailTask 实体

```java
@Data
public class EmailTask {
    private String taskId;      // UUID，唯一标识
    private String email;       // 收件人邮箱
    private String code;        // 验证码
    private String type;        // REGISTER / LOGIN / RESET_PASSWORD
    private int retryCount;     // 当前重试次数
    private long createdAt;     // 创建时间戳 (ms)
    private long expireAt;      // 过期时间戳 (ms)
    private String traceId;     // 请求链路 traceId
    private String requestIp;   // 请求来源 IP
    private String failureReason; // 失败原因
}
```

## 9. 配置参数

```yaml
mail:
  verify-code:
    expire-minutes: 15            # 验证码有效期
    limit-expire-seconds: 60      # 邮箱发送间隔
    max-error-count: 5            # 最多错误尝试次数
    error-expire-minutes: 5       # 错误计数过期
    ip-limit-count: 20            # IP 每小时上限
    ip-limit-expire-minutes: 10   # IP 计数过期
    email-ip-limit-count: 5       # 邮箱+IP 组合上限
    email-ip-limit-expire-minutes: 10
```

## 10. 死信审计

```java
// EmailDeadLetterConsumer — 监听 email.dlq
// 将最终发送失败的任务写入 MySQL email_send_failure 表
// 审计字段：taskId, email, type, retryCount, reason, createdAt, failedAt, traceId, expiredFlag
```

## 11. 设计要点总结

| 要点 | 说明 |
|---|---|
| 三层限流 | 邮箱/60s + IP/10min + 邮箱+IP 组合/10min |
| 异步解耦 | 生成验证码和发送邮件通过 RabbitMQ 解耦，接口快速返回 |
| 重试机制 | 发送失败自动重试 3 次，间隔 30 秒 |
| 死信审计 | 最终失败的任务写入 MySQL `email_send_failure` 表 |
| 幂等消费 | Redis `email:task:done:{taskId}` 防止重复发送 |
| 防邮箱枚举 | 未注册邮箱不发送验证码但返回相同响应 |
| Publisher Confirm | 投递消息时等待 Broker 确认，5 秒超时 |
| 验证码 SecureRandom | `RandomCodeUtil` 使用 `SecureRandom` 生成 6 位数字 |
| 验证码一次有效 | 校验成功后立即删除 Redis key |
| 错误次数限制 | 超过 5 次错误尝试后验证码自动失效 |
