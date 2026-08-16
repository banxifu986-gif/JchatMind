# 鉴权体系引入 - 2026-05-27

## 背景

JChatMind 此前完全无鉴权：用户在前端输入任意 `userId` 字符串，所有 API 作为 `@RequestParam` 查询参数透传，无密码、无 Token、无权限控制。本次按 `docs/reference/` 下三份架构设计文档（登录注册、鉴权、Redis 邮箱验证码）从零实现完整认证授权系统。

## 总体设计

- **Spring Security** 作门面（`/api/**` permitAll），不做实际拦截
- **TokenInterceptor** 解析 JWT，仅识别身份不阻断请求，永远返回 `true`
- **RequestScopeData** 请求级 `@RequestScope` Bean 存储 `token` / `userId` / `isLogin`
- **@NeedLogin / @NeedAdmin** AOP 注解驱动权限校验
- **BCrypt** 密码加密，**jjwt 0.12.6** HS512 签名，30 天过期 + `whoami()` 滑动刷新
- **Redis 三层限流**：邮箱 60s + IP 10min + 邮箱+IP 组合 10min
- **RabbitMQ 异步邮件**：`email.queue` → `email.retry.queue`（TTL 30s）→ `email.dlq` 死信审计
- **邮箱防枚举**：LOGIN 时未注册邮箱限流照常标记但不发送，响应与正常无差异

## 实施范围

### Phase 1-3 基础设施与核心鉴权

| 文件 | 说明 |
|------|------|
| `pom.xml` | 新增 spring-boot-starter-security、jjwt 0.12.6、spring-boot-starter-aop、spring-boot-starter-data-redis/amqp、spring-boot-starter-thymeleaf |
| `application.yaml` | 新增 jwt、redis、rabbitmq、mail.verify-code 配置段 |
| `sql/auth/2026-05-26-create-user-table.sql` | PostgreSQL `jchatmind_user` 表（BIGSERIAL 主键，UNIQUE account/email，触发器 updated_at） |
| `sql/auth/2026-05-26-create-email-failure-table.sql` | `email_send_failure` 审计表 |
| `auth/RequestScopeData.java` | 请求级 `@RequestScope` Bean |
| `auth/JwtUtil.java` | JWT 签发/校验/解析 |
| `auth/TokenInterceptor.java` | 解析 `Authorization: Bearer <token>`，永远返回 true |
| `auth/annotation/NeedLogin.java`、`NeedAdmin.java` | 方法级权限注解 |
| `auth/aspect/NeedLoginAspect.java`、`NeedAdminAspect.java` | AOP 鉴权切面 |
| `config/SecurityConfig.java` | permitAll、CSRF disable、BCryptPasswordEncoder、CORS |
| `config/WebConfig.java` | 注册 TokenInterceptor，排除 `/api/users/**`、`/api/email/**`、`/health`、`/error` |
| `exception/BizException.java` | 新增 `BizException(int code, String message)` 构造器用于 401/403 |
| `model/entity/User.java` | `jchatmind_user` 对应实体 |
| `mapper/UserMapper.java` + `UserMapper.xml` | MyBatis CRUD |
| `model/request/LoginRequest.java`、`RegisterRequest.java` | 认证请求 DTO |
| `model/vo/LoginUserVO.java`、`RegisterVO.java` | 含 token 字段的响应 VO |
| `service/UserService.java` + `UserServiceImpl.java` | register/login/whoami（BCrypt 密码、JWT 签发、滑动过期） |
| `controller/UserController.java` | `POST /api/users`、`POST /api/users/login`、`GET /api/users/whoami` |

### Phase 4 API 迁移

所有 Controller/Service 移除 `@RequestParam String userId`，改为从 `RequestScopeData` 内部获取。

关键改动：

| 文件 | 改动 |
|------|------|
| `controller/ChatSessionController.java` | 移除所有 `userId` 参数 |
| `controller/ChatMessageController.java` | 同上 |
| `controller/UserMemoryController.java` | `@RequestMapping("/api/users/{userId}")` → `"/api/users"` |
| `controller/HarnessController.java` | 移除 `userId` 参数 |
| `service/ChatSessionFacadeService.java` + Impl | 接口移除 `userId`，内部从 `RequestScopeData` 获取 |
| `service/ChatMessageFacadeService.java` + Impl | 同上 |
| `service/UserMemoryFacadeService.java` + Impl | Public 方法从 `RequestScopeData` 获取；internal 方法保留显式 `userId` 参数供 agent 异步线程使用 |
| `model/request/CreateChatSessionRequest.java` | 移除 `userId` 字段 |
| `model/request/CreateChatMessageRequest.java` | 移除 `userId` 字段 |
| `converter/ChatSessionConverter.java` | 移除 `getUserId()` 校验与赋值 |
| `agent/JChatMind.java`、`JChatMindFactory.java`、`KnowledgeTools.java` | 适配去 userId 后的方法签名 |

**线程安全**：`UserMemoryFacadeService` 的 `getConfirmedMemories()`、`recallRelevantMemories()`、`extractMemoryCandidates()` 保留显式 `String userId` 参数，因为 agent 执行在 `@Async` 线程中无 `RequestScopeData`。

### Phase 5 前端改造

**核心思路**：从 `{userId, setUserId}` 升级为 `{user, token, isLogin, loading, login, logout, refreshUser}`。

| 文件 | 改动 |
|------|------|
| `contexts/UserContextBase.ts` | `UserContext` → `AuthContext`（含 `UserInfo`/`token`/`isLogin`/`login`/`logout`/`refreshUser`），保留 `UserContext = AuthContext` 向后兼容 |
| `contexts/UserContext.tsx` | 启动时从 localStorage 读取 token 调 `whoami()` 自动登录；`login()` 存储 token；`logout()` 清除 |
| `api/http.ts` | `getAuthHeaders()` 读 localStorage token 注入 `Authorization` Header；401 时清除凭证 |
| `api/api.ts` | 移除所有 `userId` 参数；新增 `loginUser()`、`registerUser()`、`whoami()` |
| `hooks/useUser.ts` | 适配 `AuthContextType` |
| `components/SideMenu.tsx` | 替换 userId 输入框 → 已登录显示用户名+退出按钮，未登录显示登录/注册按钮 |
| `components/auth/LoginModal.tsx` | 新增，支持密码登录/验证码登录双模式 Tab 切换 |
| `components/auth/RegisterModal.tsx` | 新增，注册表单 |
| `components/views/AgentChatView.tsx` | `userId` → `isLogin`，所有 API 调用移除 userId |
| `components/views/agentChatView/EmptyAgentChatView.tsx` | 移除 userId，更新帮助文本 |
| `components/views/UserMemoryView.tsx` | `userId` → `user`，显示用户名 |
| `contexts/ChatSessionsContext.tsx` | `userId` → `isLogin`，未登录不加载会话列表 |

### Phase 6 邮箱验证码

**Redis 限流 Key 设计**（`auth/RedisKey.java`）：

| 层级 | Key 模式 | 限制 | TTL |
|------|---------|------|-----|
| L1 | `email:{type}:verification_code:limit:{email}` | 同邮箱不可重复 | 60s |
| L2 | `email:{type}:verification_code:limit:ip:{MD5}` | 同 IP 最多 20 次 | 10min |
| L3 | `email:{type}:verification_code:rate_limit:{MD5}` | 同邮箱+IP 最多 5 次 | 10min |

**RabbitMQ 拓扑**（`config/RabbitMQConfig.java`）：

```
email.exchange (Direct)
  → email.queue（绑定 DLX email.dlx）
    → EmailConsumer — 成功 → 验证码写 Redis + 幂等标记
    → 失败 + retryCount < 3 → email.retry.exchange → email.retry.queue (TTL 30s)
      → 消息过期 → dead letter 回到 email.exchange
    → retryCount >= 3 或过期 → email.dlx → email.dlq
      → EmailDeadLetterConsumer → email_send_failure 表审计
```

新增文件：

| 文件 | 说明 |
|------|------|
| `auth/RedisKey.java` | Key 工厂方法，IP 用 MD5 哈希 |
| `auth/RandomCodeUtil.java` | `SecureRandom` 6 位数字验证码 |
| `model/entity/EmailTask.java` + `EmailSendFailure.java` | 邮件任务与失败审计实体 |
| `config/RedisConfig.java` | `RedisTemplate<String, String>` |
| `config/RabbitMQConfig.java` | 完整拓扑配置 + `Jackson2JsonMessageConverter`（含 `JavaTimeModule`） |
| `service/impl/EmailConsumer.java` | 消费 email.queue，Thymeleaf 模板渲染 HTML 邮件，幂等检查 + 过期检查 + 重试/DLQ 路由 |
| `service/impl/EmailDeadLetterConsumer.java` | 消费 email.dlq，写入 MySQL 审计 |
| `service/impl/EmailServiceImpl.java` | `sendVerificationCode(shouldSend)` 三层限流 + `checkVerificationCode()` 错误计数 |
| `controller/EmailVerifyController.java` | `POST /api/email/verify-code`（email + type） |
| `mapper/EmailFailureMapper.java` + XML | `email_send_failure` 插入 |
| `templates/mail/verify-code.html` | Thymeleaf 邮件模板 |

### Phase 7 收尾

- 移除 `TokenInterceptor` 中 `?userId=` 向后兼容代码
- `.env.example` 新增 `JWT_SECRET`、`REDIS_*`、`RABBITMQ_*` 变量

## 验证

前端 build 通过：

```
npm run build → tsc -b && vite build → ✓ built in 10.52s
```

后端编译通过：

```
mvnw.cmd compile → BUILD SUCCESS
```

## 结论

- 鉴权体系从零引入完成，7 个 Phase 全部落地
- `Long userId (BIGSERIAL)` 与现有 VARCHAR 列的转换在 Service 层通过 `String.valueOf()` / `Long.valueOf()` 完成
- Agent 异步线程通过 `UserMemoryFacadeService` 内部方法显式传 `userId` 参数解决 `RequestScopeData` 不可用问题
- Redis 三层限流 + RabbitMQ 异步邮件 + 死信重试对齐参考文档全套设计
